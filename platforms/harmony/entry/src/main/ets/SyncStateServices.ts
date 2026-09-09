import { CompanionState, CompanionStateEntry } from './CompanionState';
import { ServiceHandler, ServiceReply, ServiceRequest } from './ServicePump';
import { CompanionStateWait } from '@podjs/companion/src/main/ets/CompanionMessageTransport';

class StateArgs { key: string = ''; value?: Object | null; peerId?: string; }
class StateResult { exists: boolean = false; entry?: CompanionStateEntry; }
class StateOperation { cancelWait: () => void = () => {}; }

/** Installed host owner only, behind AuthorizedServices. No guest identity,
 * pairing, capability grant or network connection is accepted here.
 * Cancellation suppresses queued work/replies, not a mutation already committed. */
export class SyncStateServices implements ServiceHandler {
  private active: Map<number, StateOperation> = new Map();
  constructor(private delegate: ServiceHandler, private state: () => Promise<CompanionState>,
    private waitState: ((peer: string) => CompanionStateWait) | null = null) {}
  handle(request: ServiceRequest, complete: (reply: ServiceReply) => void): void {
    if (!['sync.state.get', 'sync.state.set', 'sync.state.delete', 'sync.state.synchronize'].includes(request.method) ||
      (request.method === 'sync.state.synchronize' && this.waitState === null)) {
      this.delegate.handle(request, complete); return;
    }
    const input = request.args as StateArgs;
    const identity = request.method === 'sync.state.synchronize' ? input?.peerId : input?.key;
    if (!input || typeof identity !== 'string' || !/^[A-Za-z0-9_.:-]{1,128}$/.test(identity) ||
        (request.method === 'sync.state.set' && input.value === undefined)) {
      const reply = new ServiceReply(); reply.code = 'invalid_argument'; reply.message = 'Invalid sync state arguments'; complete(reply); return;
    }
    // Requests normally come from the JSON service pump. Snapshot before the
    // async boundary so later mutation of the parsed request cannot redirect IO.
    let args: StateArgs;
    try { args = JSON.parse(JSON.stringify(input)) as StateArgs; }
    catch (_) { const reply = new ServiceReply(); reply.code = 'invalid_argument'; reply.message = 'Invalid sync state arguments'; complete(reply); return; }
    const method = request.method, id = request.id;
    const operation = new StateOperation();
    this.active.get(id)?.cancelWait();
    this.active.set(id, operation);
    Promise.resolve().then(async () => {
      const current = (): boolean => this.active.get(id) === operation;
      if (!current()) return;
      const reply = new ServiceReply();
      try {
        if (method === 'sync.state.synchronize') {
          const wait = (this.waitState as (peer: string) => CompanionStateWait)(args.peerId as string);
          operation.cancelWait = () => wait.cancel();
          if (!current()) wait.cancel();
          reply.value = { appliedCursor: await wait.done };
        } else {
          const state = await this.state(); if (!current()) return;
          if (method === 'sync.state.get') {
            const snapshot = await state.snapshot(); const result = new StateResult();
            result.entry = snapshot.entries.find((entry: CompanionStateEntry) => entry.key === args.key);
            result.exists = result.entry !== undefined && !result.entry.deleted; reply.value = result;
          } else if (method === 'sync.state.set') reply.value = await state.set(args.key, args.value as Object | null);
          else reply.value = await state.delete(args.key);
        }
        reply.ok = true;
      } catch (_) { reply.code = 'host_error'; reply.message = 'Sync state operation failed'; }
      if (!current()) return;
      this.active.delete(id); complete(reply);
    });
  }
  cancel(id: number): void { const operation = this.active.get(id); this.active.delete(id); operation?.cancelWait(); this.delegate.cancel(id); }
}
