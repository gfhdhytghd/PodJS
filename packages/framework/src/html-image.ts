/** Source pixels and ratio hints from common lazy-image HTML. Percent layout
 * dimensions do not describe the source. WeChat data-ratio is height / width. */
export function htmlImageAttributes(attrs:Readonly<Record<string,string>>):{url:string;width?:number;height?:number;aspectRatio?:number} {
 const positive=(value:string|undefined)=>{const number=Number(value);return Number.isFinite(number)&&number>0?number:undefined;};
 const dimension=(name:string)=>{
  const attr=attrs[name]?.trim(),css=attrs.style?.match(new RegExp(`(?:^|;)\\s*${name}\\s*:\\s*(\\d+(?:\\.\\d+)?)px\\s*(?:;|$)`,'i'))?.[1];
  const value=positive(attr&&/^\d+(?:\.\d+)?(?:px)?$/.test(attr)?attr.replace(/px$/,''):css);
  return value===undefined?undefined:Math.round(value);
 };
 const lazy=['data-src','data-original','data-lazy-src','data-actualsrc','data-url'].map(key=>attrs[key]?.trim()).find(Boolean);
 const srcset=attrs['data-srcset']||attrs.srcset||'';
 let candidate='',weight=0;
 for(const match of srcset.matchAll(/(?:^|,)\s*(\S+)\s+(\d+(?:\.\d+)?)(?:w|x)\s*(?=,|$)/g)){
  const size=Number(match[2]);if(size>weight){candidate=match[1]!;weight=size;}
 }
 const src=attrs.src?.trim()||'';
 const placeholder=!src||/^(?:data:|about:blank)/i.test(src)||/(?:^|[\/_-])(?:loading|placeholder|spacer|blank|transparent|1x1)(?:[._/-]|$)/i.test(src)
  ||positive(attrs.width)===1&&positive(attrs.height)===1;
 const url=(placeholder?lazy:undefined)||candidate||src||lazy||'';
 let width=positive(attrs['data-w'])??positive(attrs['data-width'])??dimension('width');
 let height=positive(attrs['data-h'])??positive(attrs['data-height'])??dimension('height');
 // A lazy 1x1 src's dimensions must not become the real image's ratio.
 if(width!==undefined&&width<=1)width=undefined;
 if(height!==undefined&&height<=1)height=undefined;
 const inverse=positive(attrs['data-ratio']);
 const css=attrs.style?.match(/(?:^|;)\s*aspect-ratio\s*:\s*(\d+(?:\.\d+)?)(?:\s*\/\s*(\d+(?:\.\d+)?))?\s*(?:;|$)/i);
 const aspectRatio=inverse?1/inverse:css&&positive(css[1])&&(css[2]===undefined||positive(css[2]))?Number(css[1])/Number(css[2]??1):undefined;
 if(aspectRatio&&Number.isFinite(aspectRatio)){
  if(width&&!height)height=width/aspectRatio;
  else if(height&&!width)width=height*aspectRatio;
 }
 return {url,width,height,...(!width||!height?aspectRatio?{aspectRatio}:{}:{})};
}
