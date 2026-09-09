const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { createHash, randomUUID } = require('node:crypto');
const api = require(process.argv[2]);
const { CompanionFileRequests, CompanionFileRequest, CompanionOutgoingTransfers } = require(process.argv[3]);
function registry(root) {
  return new CompanionOutgoingTransfers('app', 'phone', {
    read: () => api.companionOutgoingTransfersRead(root, 'app'),
    compareExchange: (old, next) => api.companionOutgoingTransfersCompareExchange(root, 'app', old, next),
  });
}
function queue(root) {
  return new CompanionFileRequests('app', 'phone', {
    read: () => api.companionFileRequestsRead(root, 'app'),
    compareExchange: (old, next) => api.companionFileRequestsCompareExchange(root, 'app', old, next),
  }, { sha256: async bytes => new Uint8Array(createHash('sha256').update(bytes).digest()), messageId: async () => randomUUID() });
}
async function main() {
  if (process.argv[4] === 'registry') {
    const sdk = registry(process.argv[5]);
    assert.equal((await sdk.list())[0].manifest.transfer_id, 'file');
    await sdk.observeMissing('watch', 'file', []);
    await sdk.transition('watch', 'file', 'cancel_requested'); return;
  }
  if (process.argv[4] === 'reply') {
    const sdk = queue(process.argv[5]), pending = await sdk.next('watch'); assert.ok(pending);
    assert.equal(JSON.parse(new TextDecoder().decode(pending.payload)).method, 'chunk');
    const reply = new TextEncoder().encode(JSON.stringify({ version: 1, type: 'reply', request_sha256: pending.digest, value: { phase: 'accepted' } }));
    assert.equal(await sdk.receiveAuthenticated('watch', pending.messageId, reply), 'reply'); return;
  }
  if (process.argv[4] === 'consume') {
    const sdk = queue(process.argv[5]); assert.equal(await sdk.next('watch'), null);
    const completed = await sdk.completed('watch'); assert.equal(completed.length, 1);
    assert.equal(JSON.parse(new TextDecoder().decode(completed[0].reply)).value.phase, 'accepted');
    assert.equal(await sdk.forgetCompleted('watch', completed[0].messageId), true); return;
  }
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'podjs-file-requests-native-'));
  try {
    await api.companionStateCompareExchange(root, 'app', null, '{"state":true}');
    await api.companionOutboxCompareExchange(root, 'app', null, '{"outbox":true}');
    const sdk = queue(root), request = new CompanionFileRequest(); request.method = 'chunk'; request.transfer_id = 'file'; request.data = new Uint8Array(65536).fill(255);
    const pending = await sdk.enqueue('watch', request); assert.deepEqual(await queue(root).next('watch'), pending);
    await registry(root).register('watch', { transfer_id: 'file', size: 0, sha256: 'a'.repeat(64), chunk_hashes: [], mime: '' });
    const registryChild = spawnSync(process.execPath, [__filename, process.argv[2], process.argv[3], 'registry', root], { encoding: 'utf8' });
    assert.equal(registryChild.status, 0, registryChild.stderr);
    assert.equal((await registry(root).list())[0].phase, 'cancel_requested');
    assert.equal((await registry(root).list())[0].progressKnown, true);
    assert.equal(await api.companionOutgoingTransfersRead(root, 'other'), null);
    assert.equal(await api.companionOutgoingTransfersCompareExchange(root, 'app', null, '{}'), false);
    assert.deepEqual(await queue(root).next('watch'), pending);
    assert.equal(await api.companionFileRequestsRead(root, 'other'), null);
    assert.equal(await api.companionFileRequestsCompareExchange(root, 'app', null, '{}'), false);
    const journal = path.join(root, 'podjs-companion-file-requests-app', 'journal.json');
    fs.chmodSync(journal, 0o644); await assert.rejects(sdk.next('watch'), /Unsafe/); fs.chmodSync(journal, 0o600);
    for (const mode of ['reply', 'consume']) {
      const child = spawnSync(process.execPath, [__filename, process.argv[2], process.argv[3], mode, root], { encoding: 'utf8' });
      assert.equal(child.status, 0, child.stderr);
    }
    assert.equal(await sdk.next('watch'), null); assert.deepEqual(await sdk.completed('watch'), []);
    assert.equal(await api.companionStateRead(root, 'app'), '{"state":true}'); assert.equal(await api.companionOutboxRead(root, 'app'), '{"outbox":true}');
    console.log('Companion file requests native: full chunk request, process reopen/reply/consume, isolation, stale CAS and unsafe file checks passed');
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
