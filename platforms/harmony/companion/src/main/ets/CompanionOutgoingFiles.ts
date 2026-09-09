import { CompanionIncomingFilePort } from './CompanionIncomingFiles';
import { CompanionFileManifest, validateFileManifest } from './CompanionFileWire';
import { CompanionFileSource } from './CompanionFileSender';
import { CompanionOutgoingTransfers } from './CompanionOutgoingTransfers';

export class CompanionOutgoingFile {
  manifest: CompanionFileManifest = new CompanionFileManifest();
  phase: string = 'staging';
  peer: string = '';
}
class SourceJournal {
  schema: number = 1;
  app: string = '';
  files: CompanionOutgoingFile[] = [];
}
function copy(manifest: CompanionFileManifest): CompanionFileManifest {
  validateFileManifest(manifest); const value = new CompanionFileManifest();
  value.transfer_id = manifest.transfer_id; value.size = manifest.size; value.sha256 = manifest.sha256;
  value.mime = manifest.mime; value.chunk_hashes = manifest.chunk_hashes.slice(); return value;
}
/** Dedicated outgoing namespace, not a receiver port shared with incoming files.
 * Immutable IDs survive removal. Quota reserves both chunks and assembly bytes. */
export class CompanionOutgoingFiles {
  private app: string;
  private port: CompanionIncomingFilePort;
  constructor(app: string, port: CompanionIncomingFilePort, private transfers: CompanionOutgoingTransfers | null = null) {
    if (!/^[A-Za-z0-9_.:-]{1,128}$/.test(app)) throw new Error('invalid outgoing app');
    this.app = app; this.port = port;
  }
  private async load(): Promise<SourceJournal> {
    const raw = await this.port.readJournal();
    if (raw === null) { const journal = new SourceJournal(); journal.app = this.app; return journal; }
    if (raw.length > 4194304) throw new Error('outgoing journal too large');
    const journal = JSON.parse(raw) as SourceJournal;
    if (!journal || journal.schema !== 1 || journal.app !== this.app || !Array.isArray(journal.files) || journal.files.length > 128) throw new Error('invalid outgoing journal');
    const ids: string[] = []; let bytes = 0;
    for (const file of journal.files) {
      if (!file || !['importing', 'registering', 'staging', 'complete', 'removing', 'removed'].includes(file.phase)) throw new Error('invalid outgoing phase');
      if (file.phase === 'registering' && (typeof file.peer !== 'string' || !/^[A-Za-z0-9_.:-]{1,128}$/.test(file.peer))) throw new Error('invalid registering peer');
      validateFileManifest(file.manifest);
      if (ids.includes(file.manifest.transfer_id)) throw new Error('duplicate outgoing identity'); ids.push(file.manifest.transfer_id);
      if (file.phase !== 'removed') bytes += file.manifest.size * 2;
    }
    if (bytes > 33554432) throw new Error('outgoing quota exceeded'); return journal;
  }
  private save(journal: SourceJournal): Promise<void> { return this.port.writeJournal(JSON.stringify(journal)); }
  private find(journal: SourceJournal, id: string): CompanionOutgoingFile {
    const file = journal.files.find((value: CompanionOutgoingFile) => value.manifest.transfer_id === id);
    if (file === undefined) throw new Error('unknown outgoing file'); return file;
  }
  private async recoverFile(journal: SourceJournal, file: CompanionOutgoingFile): Promise<void> {
    if (file.phase === 'registering') {
      if (this.transfers === null) throw new Error('outgoing registration recovery unavailable');
      const records = (await this.transfers.list()).filter(value => value.manifest.transfer_id === file.manifest.transfer_id);
      if (records.length > 0) {
        if (records.length !== 1 || records[0].peer !== file.peer ||
          JSON.stringify(copy(records[0].manifest)) !== JSON.stringify(copy(file.manifest))) throw new Error('outgoing registration recovery mismatch');
        file.phase = 'complete'; await this.save(journal); return;
      }
      file.phase = 'removing'; await this.save(journal);
    }
    if (file.phase === 'importing') { file.phase = 'removing'; await this.save(journal); }
    if (file.phase === 'removing') {
      await this.port.remove('source', copy(file.manifest)); file.phase = 'removed'; await this.save(journal);
    } else if (file.phase === 'staging') await this.port.reserve('source', copy(file.manifest));
  }
  recover(): Promise<void> {
    return this.port.exclusive(async () => {
      const journal = await this.load();
      for (const file of journal.files) if (file.phase === 'importing' || file.phase === 'registering' || file.phase === 'removing') await this.recoverFile(journal, file);
      for (const file of journal.files) if (file.phase === 'staging') await this.recoverFile(journal, file);
    });
  }
  list(): Promise<CompanionOutgoingFile[]> { return this.port.exclusive(async () => (await this.load()).files); }
  prepare(manifest: CompanionFileManifest): Promise<void> {
    const stable = copy(manifest);
    return this.port.exclusive(async () => {
      const journal = await this.load();
      let file = journal.files.find((value: CompanionOutgoingFile) => value.manifest.transfer_id === stable.transfer_id);
      if (file !== undefined) {
        if (JSON.stringify(copy(file.manifest)) !== JSON.stringify(stable)) throw new Error('outgoing manifest changed');
        if (file.phase !== 'staging' && file.phase !== 'complete') throw new Error('outgoing file removed or import interrupted');
      } else {
        let bytes = stable.size * 2;
        for (const item of journal.files) if (item.phase !== 'removed') bytes += item.manifest.size * 2;
        if (bytes > 33554432 || journal.files.length >= 128) throw new Error('outgoing quota exceeded');
        file = new CompanionOutgoingFile(); file.manifest = stable; journal.files.push(file); await this.save(journal);
      }
      await this.recoverFile(journal, file);
    });
  }
  /** Fresh host snapshot only: one OS lease spans every source read, write and
   * commit. Recovery can therefore remove an importing record only after its
   * importer has released the lease (including process death). */
  importFresh(manifest: CompanionFileManifest,
    write: (put: (index: number, bytes: Uint8Array) => Promise<void>) => Promise<void>,
    cancelled: () => boolean = () => false, peer: string = ''): Promise<void> {
    const stable = copy(manifest);
    if (peer !== '' && (!/^[A-Za-z0-9_.:-]{1,128}$/.test(peer) || this.transfers === null)) return Promise.reject(new Error('invalid fresh registration'));
    return this.port.exclusive(async () => {
      const check = (): void => { if (cancelled()) throw new Error('file import cancelled'); };
      check(); const journal = await this.load(); check();
      // Drain only records proven abandoned by acquisition of this lease.
      for (const old of journal.files) if (old.phase === 'importing' || old.phase === 'registering' || old.phase === 'removing') await this.recoverFile(journal, old);
      if (journal.files.some(value => value.manifest.transfer_id === stable.transfer_id)) throw new Error('outgoing source already exists');
      let bytes = stable.size * 2;
      for (const old of journal.files) if (old.phase !== 'removed') bytes += old.manifest.size * 2;
      if (bytes > 33554432 || journal.files.length >= 128) throw new Error('outgoing quota exceeded');
      const file = new CompanionOutgoingFile(); file.manifest = stable; file.phase = 'importing'; journal.files.push(file);
      check(); await this.save(journal);
      try {
        await this.port.reserve('source', copy(stable)); check();
        let accepting: boolean = true;
        try {
          await write(async (index: number, chunk: Uint8Array) => {
            if (!accepting) throw new Error('fresh import writer closed');
            check(); await this.port.writeChunk('source', copy(stable), index, chunk.slice()); check();
          });
        } finally { accepting = false; }
        check(); await this.port.finish('source', copy(stable)); check();
        if (peer !== '' && this.transfers !== null) {
          file.peer = peer; file.phase = 'registering'; await this.save(journal);
          await this.transfers.register(peer, stable, cancelled);
        }
        file.phase = 'complete'; await this.save(journal);
      } catch (error) {
        // Preserve the original failure. A failed removal retains importing or
        // removing intent for the next exclusive recovery pass.
        try {
          if (peer !== '' && (file.phase === 'registering' || file.phase === 'complete')) {
            file.phase = 'registering'; await this.recoverFile(journal, file);
          } else { file.phase = 'removing'; await this.save(journal); await this.recoverFile(journal, file); }
        } catch (_) {}
        throw error;
      }
    });
  }
  writeChunk(id: string, index: number, bytes: Uint8Array): Promise<void> {
    const stable = bytes.slice();
    return this.port.exclusive(async () => {
      const journal = await this.load(), file = this.find(journal, id);
      if (file.phase !== 'staging') throw new Error('outgoing file not staging');
      await this.recoverFile(journal, file); await this.port.writeChunk('source', copy(file.manifest), index, stable);
    });
  }
  missing(id: string): Promise<number[]> {
    return this.port.exclusive(async () => {
      const journal = await this.load(), file = this.find(journal, id);
      if (file.phase !== 'staging' && file.phase !== 'complete') throw new Error('outgoing file removed');
      await this.recoverFile(journal, file); return this.port.missing('source', copy(file.manifest));
    });
  }
  finish(id: string): Promise<void> {
    return this.port.exclusive(async () => {
      const journal = await this.load(), file = this.find(journal, id);
      if (file.phase !== 'staging' && file.phase !== 'complete') throw new Error('outgoing file removed');
      await this.recoverFile(journal, file); await this.port.finish('source', copy(file.manifest));
      file.phase = 'complete'; await this.save(journal);
    });
  }
  readChunk(id: string, index: number): Promise<Uint8Array> {
    return this.port.exclusive(async () => {
      const journal = await this.load(), file = this.find(journal, id);
      if (file.phase === 'registering') await this.recoverFile(journal, file);
      if (file.phase !== 'complete') throw new Error('outgoing file not complete');
      return this.port.readCompleteChunk('source', copy(file.manifest), index);
    });
  }
  remove(id: string): Promise<void> {
    return this.port.exclusive(async () => {
      const journal = await this.load(), file = this.find(journal, id);
      if (file.phase === 'removed') return;
      file.phase = 'removing'; await this.save(journal); await this.recoverFile(journal, file);
    });
  }
}
export class CompanionStoredFileSource implements CompanionFileSource {
  private files: CompanionOutgoingFiles;
  private id: string;
  constructor(files: CompanionOutgoingFiles, id: string) { this.files = files; this.id = id; }
  readChunk(index: number): Promise<Uint8Array> { return this.files.readChunk(this.id, index); }
}
