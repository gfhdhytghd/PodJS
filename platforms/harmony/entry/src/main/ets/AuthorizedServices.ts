import { ServiceHandler, ServiceReply, ServiceRequest } from './ServicePump';

export interface ServiceAuthority { hasCapability(name: string): boolean; }

function capabilityFor(method: string): string {
  switch (method) {
    case 'sync.state.get': case 'sync.state.set': case 'sync.state.delete':
    case 'sync.state.synchronize': return 'companion.sync.state';
    case 'sync.messages.send': case 'sync.messages.ack': return 'companion.sync.message';
    case 'sync.files.offer': case 'sync.files.accept': case 'sync.files.cancel':
    case 'sync.files.status': case 'sync.files.save': return 'companion.sync.file';
    case 'notifications.status': case 'notifications.requestPermission':
    case 'notifications.schedule': case 'notifications.cancel':
    case 'notifications.listPending': return 'notification.local';
    case 'notifications.registerRemote': case 'notifications.unregisterRemote': return 'notification.remote';
    case 'background.register': case 'background.cancel': case 'background.status': return 'background.scheduled';
    default: return '';
  }
}

/** Trusted native authority, never a capability list supplied in request args.
 * This gate must wrap platform IO handlers, including permission UI requests. */
export class AuthorizedServices implements ServiceHandler {
  private active: Map<number, ServiceRequest> = new Map();
  constructor(private authority: ServiceAuthority, private delegate: ServiceHandler) {}
  handle(request: ServiceRequest, complete: (reply: ServiceReply) => void): void {
    const capability = capabilityFor(request.method);
    if (!capability || !this.authority.hasCapability(capability)) {
      const reply = new ServiceReply(); reply.code = 'unsupported';
      reply.message = 'Service capability is not authorized'; complete(reply); return;
    }
    this.active.set(request.id, request);
    try {
      this.delegate.handle(request, (reply: ServiceReply) => {
        if (this.active.get(request.id) !== request) return;
        this.active.delete(request.id); complete(reply);
      });
    } catch (error) { this.active.delete(request.id); throw error; }
  }
  cancel(id: number): void {
    if (this.active.delete(id)) this.delegate.cancel(id);
  }
}
