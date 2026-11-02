/** Geometry shared by native-surface and poster pages. */
export type VideoPagerDirection='up'|'down';
export const VIDEO_PAGER_SNAP_POSITIONAL_THRESHOLD=.18;
export const VIDEO_PAGER_MIN_FLING_VELOCITY=125;
export const VIDEO_PAGER_SETTLE_DURATION_MS=220;
export function videoPagerProgress(distanceY:number,height:number){return height>0?Math.max(0,Math.min(1,Math.abs(distanceY)/height)):0;}
export function shouldSettleVideoPager(progress:number,velocityY:number,direction:VideoPagerDirection){
  if(Math.abs(velocityY)>=VIDEO_PAGER_MIN_FLING_VELOCITY)return direction==='up'?velocityY<0:velocityY>0;
  return progress>=VIDEO_PAGER_SNAP_POSITIONAL_THRESHOLD;
}
/** Commit within a 200ms gesture cadence, leaving time to decode the new
 * neighbour. Cancellation keeps the slower rebound and never stops playback. */
export function videoPagerSettleDuration(to:0|1){return to===1?96:VIDEO_PAGER_SETTLE_DURATION_MS;}
export function videoPagerSettleProgress(from:number,to:0|1,elapsedMs:number){
  const t=Math.max(0,Math.min(1,elapsedMs/videoPagerSettleDuration(to)));return from+(to-from)*(1-Math.pow(1-t,3));
}
export function videoPagerOffsets(progress:number,height:number,direction:VideoPagerDirection){
  const sign=direction==='up'?-1:1;return {outgoing:sign*progress*height,incoming:-sign*(1-progress)*height};
}
/** Fast releases cross two items; slower gestures keep the positional rule. */
export const VIDEO_PAGER_SKIP_VELOCITY=1800;
export function videoPagerDelta(progress:number,velocityY:number,direction:VideoPagerDirection):0|1|2 {
  if(!shouldSettleVideoPager(progress,velocityY,direction))return 0;
  return Math.abs(velocityY)>=VIDEO_PAGER_SKIP_VELOCITY?2:1;
}
/** First-frame waits never flash a spinner. The caller supplies its frame clock. */
export class VideoFirstFrameWait {
  private started=0;
  private rendered=false;
  constructor(private readonly now:()=>number=Date.now,readonly delayMs=400){}
  reset(){this.started=this.now();this.rendered=false;}
  observe(rendered:boolean){if(rendered)this.rendered=true;}
  visible(){return !this.rendered&&this.now()-this.started>=this.delayMs;}
}
