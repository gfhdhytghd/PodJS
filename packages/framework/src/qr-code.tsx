import { createEffect, onCleanup } from "solid-js";
import { Image, View, type NodeMirror } from "@pocketjs/framework/components";
import { getOps } from "@pocketjs/framework/host";
import { qrPixels } from "./qr-pixels";


export function QRCode(props: { value: string; size?: number; raster?:(value:string,dimension:number)=>Uint8Array; pixelSize?:number; invalidSizeMessage?:string; uploadErrorMessage?:string; onError?: (message:string)=>void }) {
  let node: NodeMirror | undefined;
  createEffect(() => {
    if (!node || !props.value) return;
    const ops = getOps(); let handle = -1;
    try {
      const dimension=props.pixelSize??256;
      if(!Number.isInteger(dimension)||dimension<1||dimension>512)throw Error(props.invalidSizeMessage??"Invalid QR pixel size");
      const textureDimension=2**Math.ceil(Math.log2(dimension)),source=(props.raster??qrPixels)(props.value,dimension);
      let texture=source;
      if(textureDimension!==dimension){texture=new Uint8Array(textureDimension*textureDimension*4);for(let row=0;row<dimension;row++)texture.set(source.subarray(row*dimension*4,(row+1)*dimension*4),row*textureDimension*4);}
      handle = ops.uploadTexture(texture, textureDimension, textureDimension, 3);
      if (handle < 0) throw new Error(props.uploadErrorMessage??"QR texture upload failed");
      ops.setImage(node.id, handle);
    } catch(error) { props.onError?.(String(error)); }
    onCleanup(() => { if(handle >= 0) { if(node) ops.setImage(node.id, -1); ops.freeTexture?.(handle); } });
  });
  if(props.pixelSize!==undefined){
    const imageSize=()=>(props.size??150)*(2**Math.ceil(Math.log2(props.pixelSize!)))/props.pixelSize!;
    return <View class="overflow-hidden" style={{width:props.size??150,height:props.size??150}}><Image class="absolute left-[0] top-[0]" ref={value=>{node=value;}} style={{width:imageSize(),height:imageSize()}}/></View>;
  }
  return <Image ref={value => {node=value;}} style={{width:props.size??150,height:props.size??150}} />;
}
