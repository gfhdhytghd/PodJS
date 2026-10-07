import {prepareTextLayout,type TextLayoutSliceOptions} from './text-layout.ts';
import {parseHtmlFragmentSteps,type HtmlElement} from './html.ts';
/** Cancellable default host scheduler; late results cannot publish. */
export function requestHtmlFragment(source:string,publish:(tree:HtmlElement|null)=>void,options:TextLayoutSliceOptions={},skipTags?:readonly string[]) {
 let index=0;return prepareTextLayout(parseHtmlFragmentSteps(source,skipTags),{key:()=>index++,entry:()=>undefined,publish:({layout})=>publish(layout)},options);
}

