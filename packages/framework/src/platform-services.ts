import { hostOperation, HostServiceError } from "./services.ts";
import { __onHostEvent, hasCapability } from "./watch.ts";
import type { PodCapabilityId } from "./targets.ts";
import type { SyncValue, StateEntry } from "./sync-state.ts";

function id(value: string): string {
  if (typeof value !== "string" || !/^[A-Za-z0-9_.:-]{1,128}$/.test(value)) throw new HostServiceError("invalid_argument", "Invalid identifier");
  return value;
}
function subscribe<T>(type: string, capability: PodCapabilityId, handler: (value: T) => void): () => void {
  if (!hasCapability(capability)) throw new HostServiceError("unsupported", capability);
  return __onHostEvent(event => { if (event.t === type) handler(event.value as T); });
}
export interface SyncMessage { messageId: string; payload: SyncValue; ttlMs: number; priority: "normal" | "high" }
export interface FileTransfer { transferId: string; state: "offered" | "transferring" | "complete" | "cancelled" | "failed"; receivedBytes: number; totalBytes: number }
export const syncState = {
  get(key: string) { return hostOperation<{ exists: boolean; entry?: StateEntry }>("sync.state.get", { key: id(key) }); },
  set(key: string, value: SyncValue) { return hostOperation<StateEntry>("sync.state.set", { key: id(key), value }); },
  delete(key: string) { return hostOperation<StateEntry>("sync.state.delete", { key: id(key) }); },
  synchronize(peerId: string) { return hostOperation<{ appliedCursor: number }>("sync.state.synchronize", { peerId: id(peerId) }); },
  subscribe(handler: (entry: StateEntry) => void) { return subscribe("sync.state.changed", "companion.sync.state", handler); },
};
export const syncMessages = {
  send(peerId: string, message: SyncMessage) {
    id(message.messageId);
    if (!Number.isSafeInteger(message.ttlMs) || message.ttlMs <= 0 || !["normal", "high"].includes(message.priority)) throw new HostServiceError("invalid_argument", "Invalid message options");
    return hostOperation<{ messageId: string; state: "queued" }>("sync.messages.send", { peerId: id(peerId), message });
  },
  ack(peerId: string, messageId: string) { return hostOperation<void>("sync.messages.ack", { peerId: id(peerId), messageId: id(messageId) }); },
  subscribe(handler: (event: { peerId: string; message: SyncMessage }) => void) { return subscribe("sync.message.received", "companion.sync.message", handler); },
};
export const syncFiles = {
  save(transferId: string, path: string) { return hostOperation<{ path: string; size: number }>("sync.files.save", { transferId: id(transferId), path }); },
  offer(peerId: string, path: string, mime: string) { return hostOperation<FileTransfer>("sync.files.offer", { peerId: id(peerId), path, mime }); },
  accept(transferId: string) { return hostOperation<FileTransfer>("sync.files.accept", { transferId: id(transferId) }); },
  cancel(transferId: string) { return hostOperation<void>("sync.files.cancel", { transferId: id(transferId) }); },
  status(transferId: string) { return hostOperation<FileTransfer>("sync.files.status", { transferId: id(transferId) }); },
  subscribe(handler: (event: FileTransfer) => void) { return subscribe("sync.file.changed", "companion.sync.file", handler); },
};
export interface NotificationRequest {
  id: string; title: string; body: string; at?: number; category?: string;
  actions?: { id: string; title: string }[]; payload?: SyncValue;
}
export interface NotificationEvent { eventId: string; notificationId: string; actionId?: string; payload?: SyncValue }
type NotificationHandler = (event: NotificationEvent) => void | Promise<void>;
const notificationHandlers = new Map<string, Set<NotificationHandler>>();
const notificationDeliveries = new Map<string, Promise<void>>();
const notificationDelivered = new Set<string>();
function acknowledgeNotification(eventId: string) {
  const host = (globalThis as {pod?: {emit(line: string): void}}).pod;
  host?.emit(JSON.stringify({t: "notification.ack", eventId}));
}
__onHostEvent(event => {
  if (event.t !== "notification.open" && event.t !== "notification.action") return;
  const value = event.value as NotificationEvent | undefined;
  if (!value || typeof value.eventId !== "string") return;
  const eventId = value.eventId;
  if (notificationDelivered.has(eventId)) { acknowledgeNotification(eventId); return; }
  if (notificationDeliveries.has(eventId)) return;
  const handlers = [...(notificationHandlers.get(event.t) ?? [])];
  if (!handlers.length) return; // Keep the host inbox until a subscriber is ready.
  const delivery = Promise.all(handlers.map(handler => Promise.resolve().then(() => handler(value)))).then(() => {
    notificationDelivered.add(eventId);
    if (notificationDelivered.size > 256) notificationDelivered.delete(notificationDelivered.values().next().value!);
    acknowledgeNotification(eventId);
  }).catch(() => { /* Host retries; consumers must use eventId for idempotency. */ }).finally(() => notificationDeliveries.delete(eventId));
  notificationDeliveries.set(eventId, delivery);
});
function onNotification(type: string, handler: NotificationHandler) {
  if (!hasCapability("notification.local")) throw new HostServiceError("unsupported", "notification.local");
  let handlers = notificationHandlers.get(type);
  if (!handlers) notificationHandlers.set(type, handlers = new Set());
  handlers.add(handler); return () => { handlers!.delete(handler); };
}
export type NotificationPermission = "notDetermined" | "granted" | "denied" | "provisional";
export const notifications = {
  permission() { return hostOperation<NotificationPermission>("notifications.status", {}); },
  status() { return hostOperation<NotificationPermission>("notifications.status", {}); },
  requestPermission() { return hostOperation<NotificationPermission>("notifications.requestPermission", {}); },
  schedule(request: NotificationRequest) {
    id(request.id);
    if (request.at !== undefined && (!Number.isSafeInteger(request.at) || request.at < 0)) throw new HostServiceError("invalid_argument", "Invalid notification time");
    return hostOperation<void>("notifications.schedule", { notification: request });
  },
  cancel(notificationId: string) { return hostOperation<void>("notifications.cancel", { id: id(notificationId) }); },
  listPending() { return hostOperation<NotificationRequest[]>("notifications.listPending", {}); },
  registerRemote() { return hostOperation<{ platform: string; token: string }>("notifications.registerRemote", {}); },
  unregisterRemote() { return hostOperation<void>("notifications.unregisterRemote", {}); },
  onOpen(handler: NotificationHandler) { return onNotification("notification.open", handler); },
  onAction(handler: NotificationHandler) { return onNotification("notification.action", handler); },
};
export type BackgroundResult = "success" | "retry" | "failure";
export interface BackgroundTask { id: string; handler: string; earliestAt: number; intervalMs?: number; requiresNetwork?: boolean; payload?: SyncValue }
export interface BackgroundStatus { id: string; state: "scheduled" | "running" | "completed" | "cancelled" | "failed"; result?: BackgroundResult }
export const background = {
  register(task: BackgroundTask) {
    id(task.id); id(task.handler);
    if (!Number.isSafeInteger(task.earliestAt) || task.earliestAt < 0 ||
        (task.intervalMs !== undefined && (!Number.isSafeInteger(task.intervalMs) || task.intervalMs <= 0))) throw new HostServiceError("invalid_argument", "Invalid task schedule");
    return hostOperation<BackgroundStatus>("background.register", { task });
  },
  cancel(taskId: string) { return hostOperation<void>("background.cancel", { id: id(taskId) }); },
  status(taskId: string) { return hostOperation<BackgroundStatus>("background.status", { id: id(taskId) }); },
};
