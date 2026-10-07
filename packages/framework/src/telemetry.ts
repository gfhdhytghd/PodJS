/** Portable cumulative counters: the application defines event names and storage policy. */
export interface CounterStorage { get(key:string):string|undefined; set(key:string,value:string):void }
export interface DailyCounterOptions {key:string;utcOffsetMinutes:number;retentionDays:number;successEvents?:readonly string[]}
export interface DailyTelemetrySnapshot {
  day:string; eventCounts:Record<string,number>; screenOpenCounts:Record<string,number>;
  screenDurationMs:Record<string,number>; appForegroundMs:number; syncSuccessCount:number; syncFailureCount:number;
}
const DAY=86_400_000;
const empty=(day:string):DailyTelemetrySnapshot=>({day,eventCounts:{},screenOpenCounts:{},screenDurationMs:{},appForegroundMs:0,syncSuccessCount:0,syncFailureCount:0});
const counts=(value:Record<string,number>={})=>Object.fromEntries(Object.entries(value).map(([key,n])=>[key,Math.max(0,Number(n)||0)]));
/** Daily cumulative rollups normalize older sparse rows on read.
 * Uploaded days remain in the backlog. Pruning happens only on capture. */
export class DailyCounterStore {
  constructor(protected readonly storage:CounterStorage, private readonly options:DailyCounterOptions, private readonly now:()=>number=Date.now){}
  snapshots():DailyTelemetrySnapshot[] {
    try{return (JSON.parse(this.storage.get(this.options.key)??'[]') as DailyTelemetrySnapshot[])
      .filter(row=>/^\d{4}-\d{2}-\d{2}$/.test(row.day)&&Number.isFinite(Date.parse(row.day)))
      .map(row=>({...empty(row.day),eventCounts:counts(row.eventCounts),screenOpenCounts:counts(row.screenOpenCounts),screenDurationMs:counts(row.screenDurationMs),appForegroundMs:Number(row.appForegroundMs)||0,syncSuccessCount:Number(row.syncSuccessCount)||0,syncFailureCount:Number(row.syncFailureCount)||0}))
      .sort((a,b)=>a.day.localeCompare(b.day));}catch{return [];}
  }
  record(event:string,properties:{screen?:string;durationMs?:number}={}):void {
    const shifted=this.now()+this.options.utcOffsetMinutes*60*1000;
    const day=new Date(shifted).toISOString().slice(0,10);
    const oldest=new Date(shifted-(this.options.retentionDays-1)*DAY).toISOString().slice(0,10);
    const snapshots=this.snapshots().filter(row=>row.day>=oldest);
    let today=snapshots.find(row=>row.day===day);
    if(!today){today=empty(day);snapshots.push(today);}
    const increment=(map:Record<string,number>,key:string,n=1)=>{Object.defineProperty(map,key,{value:(Object.hasOwn(map,key)?map[key]:0)+n,enumerable:true,writable:true,configurable:true});};
    increment(today.eventCounts,event);
    const screen=properties.screen, duration=Math.max(0,Math.trunc(properties.durationMs??0))||0;
    if(event==='screen_opened'&&screen?.trim())increment(today.screenOpenCounts,screen);
    if(event==='screen_duration'){
      if(screen?.trim()&&duration>0)increment(today.screenDurationMs,screen,duration);
      today.appForegroundMs+=duration;
    }
    if(this.options.successEvents?.includes(event))today.syncSuccessCount++;
    this.storage.set(this.options.key,JSON.stringify(snapshots.sort((a,b)=>a.day.localeCompare(b.day))));
  }
  backlogCount():number{return this.snapshots().length;}
}

/** Coalesces captures; captures during upload schedule another pass. Failed sends
 * await the next capture; successful cumulative snapshots are not dequeued. */
export class CaptureUploadScheduler {
  private scheduled=false;
  private generation=0;
  constructor(private readonly delay:(ms:number)=>Promise<unknown>,private readonly upload:()=>Promise<void>,private readonly debounceMs=750){}
  capture(){this.generation++;if(!this.scheduled){this.scheduled=true;void this.run();}}
  private async run(){
    let uploadingGeneration=this.generation;
    try{await this.delay(this.debounceMs);uploadingGeneration=this.generation;await this.upload();}catch{/* Retry on capture. */}
    finally{this.scheduled=false;if(this.generation!==uploadingGeneration){this.scheduled=true;void this.run();}}
  }
}

/** Event times are captured before asynchronous battery reads. Reads are serialized
 * so a late start result cannot overwrite a stopped or replacement session. */
export class ActiveBatterySession {
  private current?:{source:string;started:number;level:number|null};
  private tail:Promise<void>=Promise.resolve();
  private desired?:string;
  constructor(private readonly battery:()=>Promise<number|null>,private readonly report:(sample:{source:string;durationMs:number;batteryStart:number;batteryEnd:number})=>void,
    private readonly elapsed:()=>number=()=>globalThis.performance?.now?.()??Date.now(),private readonly minDurationMs=60000){}
  update(source:string|undefined,active:boolean):Promise<void>{
    const next=active?source:undefined;if(next===this.desired)return this.tail;
    this.desired=next;const at=this.elapsed();
    this.tail=this.tail.catch(()=>{}).then(async()=>{
      const prior=this.current;this.current=undefined;
      const level=await this.battery().catch(()=>null);
      const valid=(n:number|null):n is number=>n!==null&&Number.isInteger(n)&&n>=0&&n<=100;
      if(prior&&valid(prior.level)&&valid(level)&&level<=prior.level&&at-prior.started>=this.minDurationMs)
        this.report({source:prior.source,durationMs:at-prior.started,batteryStart:prior.level,batteryEnd:level});
      if(next)this.current={source:next,started:at,level:valid(level)?level:null};
    });return this.tail;
  }
  stop(){return this.update(undefined,false);}
}
