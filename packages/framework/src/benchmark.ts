/** Synchronous, closed release observations. Native owns uptime and global seq.
 * A zero result means unsupported/rejected. There is no console/log fallback. */
export type StartupStage='stock_saved'|'process_start'|'home_mounted'|'home_tap'|'entry_mounted'|'play_tap'|'headers_ready'|'preview_start'|'preview_ready'|'source_ready'|'persist_start'|'persist_done'|'backdrop_start'|'backdrop_done'|'player_mounted'|'prepare_start'|'prepare_admitted'|'player_constructed'|'prepare_dispatched'|'video_format_ready'|'decoder_configured'|'decoded_first_frame'|'initial_frame_adopted';
export type BenchmarkCode='startup_stage'|'home_first_frame'|'douyin_request'|'entry_start'|'home_entry'|'first_card'|'refresh_start'|'refresh_done'|'player_visible'|'gesture_down'|'gesture_release'|'previous_audio_stopped'|'incoming_first_frame'|'spinner_shown'|'snap_back'|'fling_done'|'mute_changed'|'player_audio_applied'|'mixer_match'|'login_entry'|'login_tap'|'qr_ready'|'scan_accepted'|'auth_persisted'|'logout_start'|'logout_feedback'|'auth_cleared'|'auth_state'|'needs_login'|'refreshing'|'restore_state'|'renderer_process'|'prewarm_requested'|'prewarm_ready'|'prewarm_evicted'|'prewarm_policy'|'background_gate'|'error_page'|'error';
export type FailureCode='resource_exhausted'|'decode_error'|'decoder_init'|'format_error'|'surface_error'|'invalid_argument'|'permission_denied'|'cancelled'|'stale'|'not_ready'|'session_missing'|'timeout'|'network_error'|'cdn_error'|'restricted'|'source_expired'|'prepare_failed'|'video_session_error'|'video_error'|'service_closed'|'host_error'|'unsupported'|'recovery_exhausted'|'cache_error'|'storage_error'|'unknown';
export type FailureStage='capabilities'|'prepare'|'play'|'state'|'bounds'|'transform'|'loop'|'volume'|'pause'|'seek'|'stop'|'release'|'retire'|'promote'|'transition'|'recovery'|'entry'|'playback'|'capture'|'source'|'persist'|'backdrop'|'unknown';
export type ExceptionKind='media3'|'decoder_init'|'codec'|'surface'|'oom'|'io'|'illegal_state'|'illegal_argument'|'security'|'host_service'|'error'|'unknown';
export type FailureOrigin='guest'|'sessions'|'legacy';
export type CodecKind='avc'|'hevc'|'vp9'|'av1'|'unknown';
export type ContentKind='mp4'|'webm'|'hls'|'dash'|'octet'|'html'|'unknown';
export type SourceKind='cache'|'network'|'local'|'missing';
export type SurfaceState='attached'|'missing'|'detached'|'unknown';
export interface BenchmarkFields {startup_stage?:StartupStage;duration_ms?:number;system_index?:number;system_max?:number;system_muted?:boolean;audio_gain_milli?:number;audio_track?:boolean;visibility_reason?:'ready'|'inactive'|'route_offset'|'lifecycle'|'player_error'|'network_prompt'|'native_hidden'|'native_unfocused';player_state?:'idle'|'preparing'|'buffering'|'ready'|'playing'|'paused'|'ended'|'stopped'|'error'|'unknown';has_frame?:boolean;offset_abs_milli?:number;failure_code?:FailureCode;failure_stage?:FailureStage;exception_kind?:ExceptionKind;failure_origin?:FailureOrigin;codec_kind?:CodecKind;content_kind?:ContentKind;source_kind?:SourceKind;surface_state?:SurfaceState;is_prewarm?:boolean;media3_code?:number;video_width?:number;video_height?:number;decoder_count?:number;slot_limit?:number;category?:'feed'|'detail'|'auth_probe'|'media';http_status?:number;douyin_status_code?:number;challenge?:boolean;signing_ok?:boolean;cookie_present?:boolean;retry_after_ms?:number;error_code?:'success'|'forbidden'|'http'|'signing'|'challenge'|'empty'|'blocked'|'api_status'|'timeout'|'cancelled'|'network'|'cooldown'|'client_limit'|'decode';attempt?:number;direction?:'next'|'prev'|'cancel'|'fling';prewarmed?:boolean;generation?:number;source_generation?:number;delta?:number;reason?:'stale'|'capacity'|'release'|'failure'|'bidirectional'|'single_neighbour'|'foreground_media'|'media_stopped'|'timeout'|'candidate_retry'|'exhausted'|'hidden'|'network_stalled';network_stalled?:boolean;buffered_ms?:number;slots?:number;state?:'running'|'idle'|'unknown'|'present'|'absent'|'paused';muted?:boolean;kind?:'on'|'off';pid?:number;active_tracks?:number}
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
