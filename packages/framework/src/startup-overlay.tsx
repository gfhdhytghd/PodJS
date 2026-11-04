import {Show,createEffect,createSignal,type JSX} from 'solid-js';
import {onFrame} from '@pocketjs/framework/lifecycle';
import {STARTUP_EXIT_DURATION_MS} from './startup-transition.ts';
export interface StartupOverlayProps {
 ready:()=>boolean;exit:()=>boolean;
 content:()=>JSX.Element;
 overlay:(elapsed:()=>number)=>JSX.Element;
 durationMs?:number;
 clock?:()=>number;
 onExitStart?:()=>void;onFinished?:()=>void;
}
/** Content mounts when ready; the overlay stays until its exit frame finishes.
 * Revoking exit resets elapsed and allows another fade; unmount removes onFrame.
 * Must be constructed under a Solid owner, like other UI components. */
export function StartupOverlayTransition(props:StartupOverlayProps){
 const [elapsed,setElapsed]=createSignal(0),[finished,setFinished]=createSignal(false);let started:number|undefined;
 const now=props.clock??(()=>globalThis.performance?.now?.()??Date.now());
 createEffect(()=>{if(!props.exit()){started=undefined;setElapsed(0);setFinished(false);return;}if(started===undefined){props.onExitStart?.();started=now();setElapsed(0);setFinished(false);}});
 onFrame(()=>{if(started===undefined||finished())return;const value=Math.max(0,now()-started);setElapsed(value);if(value>=(props.durationMs??STARTUP_EXIT_DURATION_MS)){props.onFinished?.();setFinished(true);}},()=>started!==undefined&&!finished());
 return <><Show when={props.ready()}>{props.content()}</Show><Show when={!finished()}>{props.overlay(elapsed)}</Show></>;
}
