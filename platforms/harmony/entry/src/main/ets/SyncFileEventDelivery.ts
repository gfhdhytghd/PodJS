export interface SyncFileEventDelivery {
  setEventsActive(value: boolean): void;
  pumpEvents(post: (json: string) => boolean): Promise<void>;
}
