const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createHash } = require('node:crypto');
const native = require(process.argv[2]);
const hash = data => createHash('sha256').update(data).digest('hex');
async function main() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'podjs-incoming-napi-'));
  let lease;
  try {
    const bytes = Uint8Array.from({ length: 65539 }, (_, i) => i % 251);
    const chunks = [bytes.slice(0, 65536), bytes.slice(65536)];
    const manifest = { transfer_id: 'file', size: bytes.length, sha256: hash(bytes), mime: '', chunk_hashes: chunks.map(hash) };
    const run = (method, extra = {}) => native.incomingFilesRun(lease, { method, peer: 'phone', manifest, ...extra });
    lease = await native.incomingFilesOpen(root, 'app');
    await assert.rejects(native.outgoingFilesOpen(root, 'app'), /already owned/);
    native.incomingFilesClose(lease);
    const outgoing = await native.outgoingFilesOpen(root, 'app');
    try {
      await native.incomingFilesRun(outgoing, { method: 'writeJournal', text: '{"kind":"source"}' });
      assert.equal(await native.incomingFilesRun(outgoing, { method: 'readJournal' }), '{"kind":"source"}');
      await assert.rejects(native.outgoingFilesOpen(root, 'app'));
    } finally { native.incomingFilesClose(outgoing); }
    lease = await native.incomingFilesOpen(root, 'app');
    assert.equal(await run('readJournal'), null);
    await assert.rejects(native.incomingFilesOpen(root, 'app'));
    assert.equal(await run('readJournal'), null);
    await run('writeJournal', { text: '{"phase":"accepted"}' });
    await run('reserve');
    await assert.rejects(run('readCompleteChunk', { index: 0 }), /not complete/);
    const first = run('chunk', { index: 0, data: chunks[0] });
    assert.throws(() => run('readJournal'), /busy/);
    await first;
    assert.deepEqual(await run('missing'), [1]);
    await assert.rejects(run('finish'));
    await assert.rejects(run('chunk', { index: 1, data: new Uint8Array(3) }));
    native.incomingFilesClose(lease);
    native.incomingFilesClose(lease);
    assert.throws(() => run('readJournal'), /closed/);
    assert.throws(() => native.incomingFilesClose({}), /lease/);
    lease = await native.incomingFilesOpen(root, 'app');
    assert.equal(await run('readJournal'), '{"phase":"accepted"}');
    assert.deepEqual(await run('missing'), [1]);
    await run('chunk', { index: 1, data: chunks[1] });
    await run('finish');
    await run('finish');
    assert.deepEqual(await run('missing'), []);
    const completed = path.join(root, 'podjs-companion-incoming-app/peer-phone/file/complete');
    fs.mkdirSync(path.join(root, 'podjs-guest'), { mode: 0o700 });
    fs.mkdirSync(path.join(root, 'podjs-guest/files'), { mode: 0o700 });
    await run('saveComplete', { text: 'saved.bin' });
    await run('saveComplete', { text: 'saved.bin' });
    assert.deepEqual(fs.readFileSync(path.join(root, 'podjs-guest/files/saved.bin')), Buffer.from(bytes));
    const source = await native.guestFileOpen(root, 'saved.bin');
    assert.equal(native.guestFileSize(source), bytes.length);
    assert.deepEqual(await native.incomingFilesRun(source, { method: 'readGuestChunk', index: 0 }), chunks[0]);
    assert.deepEqual(await native.incomingFilesRun(source, { method: 'readGuestChunk', index: 1 }), chunks[1]);
    await assert.rejects(native.guestFileOpen(root, 'saved.bin'), /already active/);
    await assert.rejects(run('saveComplete', { text: 'blocked.bin' }), /already active/);
    await assert.rejects(native.incomingFilesRun(source, { method: 'readJournal' }), /storage lease/);
    await assert.rejects(native.incomingFilesRun(source, { method: 'readGuestChunk', index: 2 }), /index/);
    const sourceRead = native.incomingFilesRun(source, { method: 'readGuestChunk', index: 0 });
    native.incomingFilesClose(source); await assert.rejects(sourceRead, /closed/);
    assert.throws(() => native.guestFileSize(source), /closed/);
    const reopenedSource = await native.guestFileOpen(root, 'saved.bin'); native.incomingFilesClose(reopenedSource);
    await assert.rejects(native.guestFileOpen(root, '../escape'), /path/);
    await assert.rejects(run('saveComplete', { text: '../escape' }), /path/);
    assert.deepEqual(fs.readFileSync(completed), Buffer.from(bytes));
    assert.deepEqual(await run('readCompleteChunk', { index: 0 }), chunks[0]);
    assert.deepEqual(await run('readCompleteChunk', { index: 1 }), chunks[1]);
    await assert.rejects(run('readCompleteChunk', { index: 2 }), /index/);
    const fd = fs.openSync(completed, 'r+');
    try { fs.writeSync(fd, Buffer.from([255]), 0, 1, 65536); } finally { fs.closeSync(fd); }
    await assert.rejects(run('readCompleteChunk', { index: 1 }), /hash mismatch/);
    await assert.rejects(run('saveComplete', { text: 'corrupt.bin' }), /verification/);
    await assert.rejects(run('reserve', { manifest: { ...manifest, mime: 'changed' } }));
    assert.throws(() => run('chunk', { index: NaN, data: chunks[0] }), /number/);
    await run('remove');
    assert.equal(fs.existsSync(completed), false);
    await run('remove');
    const pending = run('readJournal');
    native.incomingFilesClose(lease);
    await assert.rejects(pending, /closed/);
    lease = await native.incomingFilesOpen(root, 'app');
    native.incomingFilesClose(lease);
    const { CompanionIncomingFiles, CompanionIncomingNativePort, CompanionFileRequest } = require(process.argv[3]);
    const port = new CompanionIncomingNativePort(native, root, 'lifecycle');
    const openReceiver = () => new CompanionIncomingFiles('lifecycle', 'watch', port, {
      async sha256(data) { return new Uint8Array(createHash('sha256').update(data).digest()); }
    });
    const command = (method, extra = {}) => Object.assign(new CompanionFileRequest(), { method, transfer_id: 'file' }, extra);
    let receiver = openReceiver();
    assert.equal((await receiver.executeAuthenticated('phone', command('offer', { manifest }))).phase, 'offered');
    const directory = path.join(root, 'podjs-companion-incoming-lifecycle/peer-phone/file');
    assert.equal(fs.existsSync(directory), false);
    await assert.rejects(receiver.readCompleteChunk('phone', 'file', 0), /not complete/);
    await assert.rejects(receiver.saveCompleteLocal('phone', 'file', 'sdk.bin'), /not complete/);
    await assert.rejects(receiver.executeAuthenticated('phone', command('missing')), /not accepted/);
    await receiver.acceptLocal('phone', 'file');
    await receiver.executeAuthenticated('phone', command('chunk', { index: 0, data: chunks[0] }));
    receiver = openReceiver();
    assert.deepEqual((await receiver.executeAuthenticated('phone', command('missing'))).missing, [1]);
    await receiver.executeAuthenticated('phone', command('chunk', { index: 1, data: chunks[1] }));
    assert.equal((await receiver.executeAuthenticated('phone', command('finish'))).phase, 'complete');
    await receiver.saveCompleteLocal('phone', 'file', 'sdk.bin');
    assert.deepEqual(fs.readFileSync(path.join(root, 'podjs-guest/files/sdk.bin')), Buffer.from(bytes));
    const { CompanionOutgoingFiles: GuestOutgoingFiles, importCompanionFile: importGuestCopy } = require(process.argv[3]);
    const sourceHandle = await native.guestFileOpen(root, 'sdk.bin');
    const outgoingFiles = new GuestOutgoingFiles('lifecycle', new CompanionIncomingNativePort({
      incomingFilesOpen: (root, app) => native.outgoingFilesOpen(root, app),
      incomingFilesClose: handle => native.incomingFilesClose(handle),
      incomingFilesRun: (handle, request) => native.incomingFilesRun(handle, request)
    }, root, 'lifecycle'));
    try {
      const copy = await importGuestCopy(outgoingFiles, {
        readChunk: index => native.incomingFilesRun(sourceHandle, { method: 'readGuestChunk', index })
      }, {
        async sha256(data) { return new Uint8Array(createHash('sha256').update(data).digest()); },
        streamingSha256() { const digest = createHash('sha256'); return { async update(data) { digest.update(data); }, async finish() { return new Uint8Array(digest.digest()); } }; }
      }, 'guest-copy', native.guestFileSize(sourceHandle), 'application/octet-stream');
      assert.equal(copy.sha256, manifest.sha256);
    } finally { native.incomingFilesClose(sourceHandle); }
    fs.writeFileSync(path.join(root, 'podjs-guest/files/sdk.bin'), 'changed after lease release');
    assert.deepEqual(await outgoingFiles.readChunk('guest-copy', 0), chunks[0]);
    assert.deepEqual(await outgoingFiles.readChunk('guest-copy', 1), chunks[1]);
    assert.deepEqual(fs.readFileSync(path.join(directory, 'complete')), Buffer.from(bytes));
    assert.deepEqual(await receiver.readCompleteChunk('phone', 'file', 0), chunks[0]);
    assert.deepEqual(await receiver.readCompleteChunk('phone', 'file', 1), chunks[1]);
    assert.equal((await receiver.executeAuthenticated('phone', command('cancel'))).phase, 'cancelled');
    assert.equal(fs.existsSync(directory), false);
    await assert.rejects(port.readJournal(), /outside transaction/);
    await port.exclusive(async () => {
      await assert.rejects(port.exclusive(async () => {}), /already active/);
      await port.readJournal();
    });
    await assert.rejects(port.exclusive(async () => { throw new Error('callback failed'); }), /callback failed/);
    await port.exclusive(async () => { await port.readJournal(); });
    const { CompanionFileSender, CompanionFileRequests, decodeFileRequest } = require(process.argv[3]);
    const { CompanionOutgoingFiles, CompanionStoredFileSource } = require(process.argv[3]);
    const sourcePort = (app = 'sender') => new CompanionIncomingNativePort({
      incomingFilesOpen: native.outgoingFilesOpen,
      incomingFilesClose: native.incomingFilesClose,
      incomingFilesRun: native.incomingFilesRun
    }, root, app);
    const faultPort = sourcePort('faults');
    const faultFiles = new CompanionOutgoingFiles('faults', faultPort);
    const reserve = faultPort.reserve.bind(faultPort);
    faultPort.reserve = async () => { throw new Error('reserve interruption'); };
    await assert.rejects(faultFiles.prepare(manifest), /reserve interruption/);
    assert.equal((await faultFiles.list())[0].phase, 'staging');
    faultPort.reserve = reserve;
    const reopenedFaults = () => new CompanionOutgoingFiles('faults', sourcePort('faults'));
    await reopenedFaults().recover();
    assert.deepEqual(await reopenedFaults().missing('file'), [0, 1]);
    await faultFiles.writeChunk('file', 0, chunks[0]);
    await faultFiles.writeChunk('file', 1, chunks[1]);
    const writeJournal = faultPort.writeJournal.bind(faultPort);
    faultPort.writeJournal = async () => { throw new Error('journal interruption'); };
    await assert.rejects(faultFiles.finish('file'), /journal interruption/);
    await assert.rejects(reopenedFaults().readChunk('file', 0), /not complete/);
    faultPort.writeJournal = writeJournal;
    await reopenedFaults().finish('file');
    assert.deepEqual(await reopenedFaults().readChunk('file', 1), chunks[1]);
    const remove = faultPort.remove.bind(faultPort);
    faultPort.remove = async () => { throw new Error('remove interruption'); };
    await assert.rejects(faultFiles.remove('file'), /remove interruption/);
    assert.equal((await reopenedFaults().list())[0].phase, 'removing');
    await assert.rejects(reopenedFaults().readChunk('file', 0), /not complete/);
    faultPort.remove = remove;
    await reopenedFaults().recover();
    assert.equal((await reopenedFaults().list())[0].phase, 'removed');
    const quotaFiles = new CompanionOutgoingFiles('quota', sourcePort('quota'));
    const maximum = { ...manifest, transfer_id: 'maximum', size: 16777216, chunk_hashes: Array(256).fill(manifest.chunk_hashes[0]) };
    await quotaFiles.prepare(maximum);
    const quotaReceiver = new CompanionIncomingNativePort(native, root, 'quota');
    await assert.rejects(quotaReceiver.exclusive(() => quotaReceiver.reserve('phone', manifest)), /app file quota exceeded/);
    await assert.rejects(quotaFiles.prepare(manifest), /quota exceeded/);
    assert.equal((await quotaFiles.list()).length, 1);
    assert.equal(fs.existsSync(path.join(root, 'podjs-companion-outgoing-quota/peer-source/file')), false);
    await quotaFiles.remove('maximum');
    await quotaReceiver.exclusive(async () => { await quotaReceiver.reserve('phone', manifest); await quotaReceiver.remove('phone', manifest); });
    await quotaFiles.prepare(manifest);
    assert.equal((await quotaFiles.list())[1].phase, 'staging');
    await quotaFiles.remove('file');
    const legacy = new CompanionIncomingNativePort(native, root, 'unaccounted');
    await legacy.exclusive(() => legacy.reserve('phone', manifest));
    fs.unlinkSync(path.join(root, 'podjs-companion-incoming-unaccounted/peer-phone/file/reservation'));
    await assert.rejects(legacy.exclusive(() => legacy.reserve('phone', { ...manifest, transfer_id: 'new' })), /Unaccounted/);
    await assert.rejects(legacy.exclusive(() => legacy.writeChunk('phone', manifest, 0, chunks[0])), /reservation/);
    await legacy.exclusive(() => legacy.remove('phone', manifest));
    await legacy.exclusive(async () => { await legacy.reserve('phone', manifest); await legacy.remove('phone', manifest); });
    const sourceFiles = () => new CompanionOutgoingFiles('sender', sourcePort());
    const { importCompanionFile } = require(process.argv[3]);
    const importCrypto = {
      async sha256(data) { return new Uint8Array(createHash('sha256').update(data).digest()); },
      streamingSha256() {
        const digest = createHash('sha256');
        return { async update(data) { digest.update(data); }, async finish() { return new Uint8Array(digest.digest()); } };
      }
    };
    const imports = new CompanionOutgoingFiles('imports', sourcePort('imports'));
    let reads = 0;
    const imported = await importCompanionFile(imports, { async readChunk(index) { reads++; return chunks[index]; } }, importCrypto, 'imported', bytes.length, '');
    assert.equal(imported.sha256, manifest.sha256); assert.equal(reads, 4);
    assert.deepEqual(await imports.readChunk('imported', 1), chunks[1]);
    reads = 0;
    await importCompanionFile(imports, { async readChunk(index) { reads++; return chunks[index]; } }, importCrypto, 'imported', bytes.length, '');
    assert.equal(reads, 2);
    await assert.rejects(importCompanionFile(imports, { async readChunk() { throw new Error('should not read'); } }, importCrypto, 'cancelled', 3, '', () => true), /cancelled/);
    reads = 0;
    await assert.rejects(importCompanionFile(imports, { async readChunk(index) {
      reads++; return reads > 2 ? new Uint8Array(chunks[index].length) : chunks[index];
    } }, importCrypto, 'mutating', bytes.length, ''), /content changed/);
    assert.deepEqual(await imports.missing('mutating'), [0, 1]);
    await assert.rejects(imports.readChunk('mutating', 0), /not complete/);
    reads = 0; let cancelImport = false;
    await assert.rejects(importCompanionFile(imports, { async readChunk(index) {
      reads++; if (reads === 4) cancelImport = true; return chunks[index];
    } }, importCrypto, 'resume', bytes.length, '', () => cancelImport), /cancelled/);
    assert.deepEqual(await imports.missing('resume'), [1]);
    const resumedImports = new CompanionOutgoingFiles('imports', sourcePort('imports'));
    reads = 0;
    await importCompanionFile(resumedImports, { async readChunk(index) { reads++; return chunks[index]; } }, importCrypto, 'resume', bytes.length, '');
    assert.equal(reads, 3);
    assert.deepEqual(await resumedImports.readChunk('resume', 1), chunks[1]);
    const freshImports = new CompanionOutgoingFiles('freshimports', sourcePort('freshimports'));
    reads = 0;
    await assert.rejects(importCompanionFile(freshImports, { async readChunk(index) {
      reads++; return reads > 2 ? new Uint8Array(chunks[index].length) : chunks[index];
    } }, importCrypto, 'changed', bytes.length, '', () => false, true), /content changed/);
    assert.equal((await freshImports.list())[0].phase, 'removed');
    assert.equal(fs.existsSync(path.join(root, 'podjs-companion-outgoing-freshimports/peer-source/changed')), false);
    const freshManifest = { ...manifest, transfer_id: 'exclusive' };
    await assert.rejects(freshImports.importFresh(freshManifest, async put => {
      await put(0, chunks[0]);
      await assert.rejects(new CompanionOutgoingFiles('freshimports', sourcePort('freshimports')).recover(), /already owned/);
      throw new Error('source interrupted');
    }), /source interrupted/);
    assert.equal((await freshImports.list()).find(file => file.manifest.transfer_id === 'exclusive').phase, 'removed');
    const freshSuccess = await importCompanionFile(freshImports, { async readChunk(index) { return chunks[index]; } },
      importCrypto, 'success', bytes.length, '', () => false, true);
    assert.equal(freshSuccess.sha256, manifest.sha256);
    assert.deepEqual(await freshImports.readChunk('success', 1), chunks[1]);
    let latePut;
    await freshImports.importFresh({ ...manifest, transfer_id: 'latewriter' }, async put => {
      latePut = put; await put(0, chunks[0]); await put(1, chunks[1]);
    });
    await assert.rejects(latePut(0, chunks[0]), /writer closed/);
    // Kill the importer with its OS lease still held and one durable chunk.
    const child = require('node:child_process').spawnSync(process.execPath, ['-e', `
      const native = require(process.argv[1]);
      const { CompanionIncomingNativePort, CompanionOutgoingFiles } = require(process.argv[2]);
      const data = new Uint8Array([1, 2, 3]);
      const hash = require('node:crypto').createHash('sha256').update(data).digest('hex');
      const port = new CompanionIncomingNativePort({ incomingFilesOpen: native.outgoingFilesOpen,
        incomingFilesClose: native.incomingFilesClose, incomingFilesRun: native.incomingFilesRun }, process.argv[3], 'crashimport');
      new CompanionOutgoingFiles('crashimport', port).importFresh({ transfer_id: 'crashed', size: 3, mime: '', sha256: hash, chunk_hashes: [hash] },
        async put => { await put(0, data); process.exit(27); }).catch(error => { console.error(error); process.exit(1); });
    `, process.argv[2], process.argv[3], root], { encoding: 'utf8', timeout: 10000 });
    assert.equal(child.status, 27, child.stderr);
    const crashedImports = new CompanionOutgoingFiles('crashimport', sourcePort('crashimport'));
    assert.equal((await crashedImports.list())[0].phase, 'importing');
    await crashedImports.recover(); assert.equal((await crashedImports.list())[0].phase, 'removed');
    assert.equal(fs.existsSync(path.join(root, 'podjs-companion-outgoing-crashimport/peer-source/crashed')), false);
    const { CompanionOutgoingTransfers } = require(process.argv[3]);
    const registrationStore = app => new CompanionOutgoingTransfers(app, 'watch', {
      read: () => native.companionOutgoingTransfersRead(root, app),
      compareExchange: (expected, desired) => native.companionOutgoingTransfersCompareExchange(root, app, expected, desired)
    });
    const registration = registrationStore('registeredimport');
    const registeredImports = new CompanionOutgoingFiles('registeredimport', sourcePort('registeredimport'), registration);
    const register = registration.register.bind(registration);
    registration.register = async (...args) => { await register(...args); throw new Error('lost registration result'); };
    await assert.rejects(importCompanionFile(registeredImports, { async readChunk(index) { return chunks[index]; } },
      importCrypto, 'uncertain', bytes.length, '', () => false, true, 'phone'), /lost registration result/);
    assert.equal((await registration.list())[0].phase, 'queued');
    assert.equal((await registeredImports.list())[0].phase, 'complete');
    assert.deepEqual(await registeredImports.readChunk('uncertain', 1), chunks[1]);
    registration.register = async () => { throw new Error('registration unavailable'); };
    await assert.rejects(importCompanionFile(registeredImports, { async readChunk(index) { return chunks[index]; } },
      importCrypto, 'unregistered', bytes.length, '', () => false, true, 'phone'), /registration unavailable/);
    assert.equal((await registeredImports.list()).find(file => file.manifest.transfer_id === 'unregistered').phase, 'removed');
    const registeredChild = require('node:child_process').spawnSync(process.execPath, ['-e', `
      const native = require(process.argv[1]);
      const { CompanionIncomingNativePort, CompanionOutgoingFiles, CompanionOutgoingTransfers } = require(process.argv[2]);
      const root = process.argv[3], app = 'crashregistered', data = new Uint8Array([1, 2, 3]);
      const hash = require('node:crypto').createHash('sha256').update(data).digest('hex');
      const port = new CompanionIncomingNativePort({ incomingFilesOpen: native.outgoingFilesOpen,
        incomingFilesClose: native.incomingFilesClose, incomingFilesRun: native.incomingFilesRun }, root, app);
      const registry = new CompanionOutgoingTransfers(app, 'watch', {
        read: () => native.companionOutgoingTransfersRead(root, app),
        compareExchange: (expected, desired) => native.companionOutgoingTransfersCompareExchange(root, app, expected, desired) });
      const register = registry.register.bind(registry);
      registry.register = async (...args) => { await register(...args); process.exit(28); };
      new CompanionOutgoingFiles(app, port, registry).importFresh({ transfer_id: 'crashed', size: 3, mime: '', sha256: hash, chunk_hashes: [hash] },
        async put => { await put(0, data); }, () => false, 'phone').catch(error => { console.error(error); process.exit(1); });
    `, process.argv[2], process.argv[3], root], { encoding: 'utf8', timeout: 10000 });
    assert.equal(registeredChild.status, 28, registeredChild.stderr);
    const registeredRecovery = new CompanionOutgoingFiles('crashregistered', sourcePort('crashregistered'), registrationStore('crashregistered'));
    assert.equal((await registeredRecovery.list())[0].phase, 'registering');
    await registeredRecovery.recover(); assert.equal((await registeredRecovery.list())[0].phase, 'complete');
    assert.deepEqual(await registeredRecovery.readChunk('crashed', 0), new Uint8Array([1, 2, 3]));
    await sourceFiles().prepare(manifest);
    await assert.rejects(sourceFiles().readChunk('file', 0), /not complete/);
    await sourceFiles().writeChunk('file', 0, chunks[0]);
    assert.deepEqual(await sourceFiles().missing('file'), [1]);
    await sourceFiles().writeChunk('file', 1, chunks[1]);
    await sourceFiles().finish('file');
    await assert.rejects(sourceFiles().prepare({ ...manifest, mime: 'changed' }), /manifest changed/);
    let serial = 0;
    const crypto = {
      async sha256(data) { return new Uint8Array(createHash('sha256').update(data).digest()); },
      async messageId() { return 'sender-' + (++serial); }
    };
    const queue = () => new CompanionFileRequests('sender', 'phone', {
      read: () => native.companionFileRequestsRead(root, 'sender'),
      compareExchange: (expected, desired) => native.companionFileRequestsCompareExchange(root, 'sender', expected, desired)
    }, crypto);
    const receive = () => new CompanionIncomingFiles('sender', 'watch', new CompanionIncomingNativePort(native, root, 'sender'), crypto);
    const drive = () => new CompanionFileSender(queue(), 'watch', manifest, new CompanionStoredFileSource(sourceFiles(), 'file'), crypto);
    let lost = false, transferCompleted = false, approved = false;
    for (let step = 0; step < 24; step++) {
      const state = await drive().step();
      if (state === 'complete') { transferCompleted = true; break; }
      const pending = await queue().next('watch'); assert.ok(pending);
      const request = decodeFileRequest(pending.payload);
      const value = await receive().executeAuthenticated('phone', request);
      if (request.method === 'offer' && !approved) {
        assert.equal(value.phase, 'offered');
        await receive().acceptLocal('phone', 'file'); approved = true;
      }
      if (request.method === 'chunk' && !lost) {
        lost = true;
        assert.equal(await drive().step(), 'awaiting_reply');
        assert.equal((await queue().next('watch')).messageId, pending.messageId);
        continue;
      }
      const response = new TextEncoder().encode(JSON.stringify({ version: 1, type: 'reply', request_sha256: pending.digest, value }));
      await queue().receiveAuthenticated('watch', pending.messageId, response);
    }
    assert.ok(transferCompleted && lost && approved);
    const actual = Buffer.concat([Buffer.from(await receive().readCompleteChunk('phone', 'file', 0)), Buffer.from(await receive().readCompleteChunk('phone', 'file', 1))]);
    assert.deepEqual(actual, Buffer.from(bytes));
    await receive().cancelLocal('phone', 'file');
    await sourceFiles().remove('file');
    await sourceFiles().remove('file');
    assert.equal((await sourceFiles().list())[0].phase, 'removed');
    await assert.rejects(sourceFiles().prepare(manifest), /removed/);
    console.log('Harmony incoming file N-API: durable chunks, restart, publish, lease lifecycle PASS');
  } finally {
    if (lease) native.incomingFilesClose(lease);
    fs.rmSync(root, { recursive: true, force: true });
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
