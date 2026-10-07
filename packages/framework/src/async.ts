/** Serializes bridge writes and exposes the exact tail that exit paths must await. */
export class SerialWriteQueue {
  private tail: Promise<void> = Promise.resolve();

  enqueue(write: () => Promise<void>): Promise<void> {
    const task = this.tail.catch(() => {}).then(write);
    this.tail = task;
    return task;
  }

  flush(): Promise<void> {
    return this.tail;
  }
}
