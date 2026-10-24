import {traceServiceWork} from './service-trace.ts';

export interface WorkSliceOptions {
  /** Must cross a host/macrotask boundary, not just resolve a Promise. */
  yieldTurn:()=>Promise<unknown>;
  check?:()=>void;
  budgetMs?:number;
  stepLimit?:number;
  clock?:()=>number;
  traceLabel?:string;
  onSlice?:(milliseconds:number,steps:number)=>void;
}
/** Consume bounded iterator steps without retaining a history of their yields.
 * Each next()/comparator must itself be bounded. Cancellation closes the input. */
export async function runWorkSteps<T>(steps:Iterator<unknown,T>,options:WorkSliceOptions):Promise<T> {
  const budget=options.budgetMs??2,limit=options.stepLimit??2048,clock=options.clock??Date.now;
  if(!Number.isFinite(budget)||budget<=0||!Number.isSafeInteger(limit)||limit<1)throw RangeError('Invalid work slice budget');
  let complete=false;
  try {
    for(;;){
      options.check?.();await options.yieldTurn();options.check?.();
      const began=clock();let count=0,result:IteratorResult<unknown,T>|undefined;
      const work=()=>{do{result=steps.next();if(result.done)break;count++;}while(count<limit&&clock()-began<budget);};
      if(options.traceLabel)traceServiceWork(options.traceLabel,work);else work();
      options.onSlice?.(clock()-began,count);options.check?.();
      if(result!.done){complete=true;return result!.value;}
    }
  }finally{if(!complete)steps.return?.();}
}

/** Stable bottom-up merge sort, one copy/comparison move per iterator step.
 * No input mutation; O(n log n) comparisons and O(n) storage even on sorted,
 * reversed and lexically ordered numeric keys. NaN compares as an equal key. */
export function* stableSortSteps<T>(input:Iterable<T>,compare:(a:T,b:T)=>number):Generator<void,T[]> {
  let source:T[]=[];for(const value of input){source.push(value);yield;}
  let target=new Array<T>(source.length);
  for(let width=1;width<source.length;width*=2){
    for(let start=0;start<source.length;start+=width*2){
      const middle=Math.min(start+width,source.length),end=Math.min(start+width*2,source.length);
      let left=start,right=middle;
      for(let at=start;at<end;at++){
        if(right>=end||left<middle&&!(compare(source[left]!,source[right]!)>0))target[at]=source[left++]!;
        else target[at]=source[right++]!;
        yield;
      }
    }
    [source,target]=[target,source];
  }
  return source;
}

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

export type FlushOutcome =
  | { status: "saved" }
  | { status: "failed"; error: unknown }
  | { status: "timed-out"; timeoutMs: number };

export interface FlushTimer {
  result: Promise<unknown>;
  cancel(): void;
}

export type FlushTimerFactory = (milliseconds: number) => FlushTimer;

/** Races a final write against a cancellable caller-supplied deadline.
 * Timeout stops waiting; it does not cancel the write. */
export async function waitForFlush(
  flush: () => Promise<void>,
  timerFactory: FlushTimerFactory,
  timeoutMs: number,
): Promise<FlushOutcome> {
  let timer: FlushTimer;
  try {
    timer = timerFactory(timeoutMs);
  } catch (error) {
    return { status: "failed", error };
  }
  const timedOut = timer.result.then<FlushOutcome, FlushOutcome>(
    () => ({ status: "timed-out", timeoutMs }),
    error => ({ status: "failed", error }),
  );
  let writing: Promise<void>;
  try {
    writing = flush();
  } catch (error) {
    timer.cancel();
    return { status: "failed", error };
  }
  try {
    return await Promise.race<FlushOutcome>([
      writing.then<FlushOutcome, FlushOutcome>(() => ({ status: "saved" }), error => ({ status: "failed", error })),
      timedOut,
    ]);
  } finally {
    timer.cancel();
  }
}

/** Deduplicates a Back attempt while the bounded final-write wait is active. */
export class ExitFlushGuard<T extends object> {
  private pending: T | undefined;

  constructor(
    private readonly timerFactory: FlushTimerFactory,
    private readonly timeoutMs: number,
  ) {}

  begin(target: T, flush: () => Promise<void>, complete: (outcome: FlushOutcome) => void): void {
    if (this.pending === target) return;
    this.pending = target;
    void waitForFlush(flush, this.timerFactory, this.timeoutMs).then(complete).finally(() => {
      if (this.pending === target) this.pending = undefined;
    });
  }
}

/** Bound synchronous work between real host completions. Waiting for I/O is
 * already a cooperative boundary and must not spend the next CPU slice. */
export class HostWorkBudget {
 private steps=0;private started:number;
 private readonly clock:()=>number;
 constructor(private readonly options:WorkSliceOptions){this.clock=options.clock??Date.now;this.started=this.clock();}
 boundary(){this.steps=0;this.started=this.clock();}
 async checkpoint(weight=1){
  this.options.check?.();this.steps+=weight;
  if(this.steps<(this.options.stepLimit??32)&&this.clock()-this.started<(this.options.budgetMs??2))return;
  await this.options.yieldTurn();this.boundary();this.options.check?.();
 }
}
