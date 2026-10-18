let sequence=0;
/** Opt-in numeric render diagnostics. A checkpoint is not a display-present fence.
 * Android logs receipt, subsequent snapshots/submits and TextureView updates.
 * Optional alpha sampling reads only this app's UI texture, never screen pixels. */
export function renderCheckpoint(options:{sampleAlpha?:boolean}={}):number|undefined {
 const pod=(globalThis as {pod?:{emit?:(line:string)=>void}}).pod;
 if(!pod?.emit)return;
 const id=++sequence;
 try{pod.emit(JSON.stringify({t:'diagnostic.renderCheckpoint',sequence:id,guestEpochMs:Date.now(),sampleAlpha:options.sampleAlpha===true}));return id;}
 catch{return;}
}
