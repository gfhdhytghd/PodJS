/** Synchronous, closed release observations. Native owns uptime and global seq.
 * A zero result means unsupported/rejected. There is no console/log fallback. */
export type BenchmarkCode='entry_start'|'home_entry'|'first_card'|'refresh_start'|'refresh_done'|'player_visible'|'gesture_down'|'gesture_release'|'previous_audio_stopped'|'incoming_first_frame'|'spinner_shown'|'snap_back'|'fling_done'|'mute_changed'|'player_audio_applied'|'mixer_match'|'login_entry'|'login_tap'|'qr_ready'|'scan_accepted'|'auth_persisted'|'logout_start'|'logout_feedback'|'auth_cleared'|'auth_state'|'needs_login'|'refreshing'|'restore_state'|'renderer_process'|'prewarm_requested'|'prewarm_ready'|'prewarm_evicted'|'prewarm_policy'|'background_gate'|'error_page'|'error';
export interface BenchmarkFields {attempt?:number;direction?:'next'|'prev'|'cancel'|'fling';prewarmed?:boolean;generation?:number;source_generation?:number;delta?:number;reason?:'stale'|'capacity'|'release'|'failure'|'bidirectional'|'single_neighbour'|'foreground_media'|'media_stopped'|'timeout'|'candidate_retry'|'exhausted'|'hidden'|'network_stalled';network_stalled?:boolean;buffered_ms?:number;slots?:number;state?:'running'|'idle'|'unknown'|'present'|'absent'|'paused';muted?:boolean;kind?:'on'|'off';pid?:number;active_tracks?:number}
export interface VideoBenchmarkAction {seq:number;generation:number;source_generation?:number;delta?:1|2}
function send(code:BenchmarkCode,fields:BenchmarkFields,seq?:number,presented=false,cancel=false):number {
 const host=(globalThis as {pod?:{benchmark?:(line:string)=>number}}).pod?.benchmark;
 if(!host)return 0;
 try {const value=host(JSON.stringify({code,...fields,...(seq?{seq}:{}),...(presented?{presented:true}:{}),...(cancel?{cancel_presented:true}:{})}));return Number.isSafeInteger(value)&&value>0?value:0;}catch{return 0;}
}
export const benchmark={
 /** Confirm UI cover removal. Native requires its own decoded, bound target and real commits. */
 confirmVideoFrame:(seq:number,generation:number)=>{
  const host=(globalThis as {pod?:{benchmark?:(line:string)=>number}}).pod?.benchmark;
  try{return seq>0&&host?host(JSON.stringify({video_frame:seq,generation})):0;}catch{return 0;}
 },
 begin:(code:BenchmarkCode,fields:BenchmarkFields={})=>send(code,fields),
 mark:(code:BenchmarkCode,seq:number,fields:BenchmarkFields={})=>seq>0?send(code,fields,seq):0,
 /** Register next real HWUI frame commit. Unsupported/non-visible hosts emit nothing. */
 cancelPresented:(code:BenchmarkCode,seq:number)=>seq>0?send(code,{},seq,false,true):0,
 presented:(code:BenchmarkCode,seq:number,fields:BenchmarkFields={})=>seq>0?send(code,fields,seq,true):0,
};
