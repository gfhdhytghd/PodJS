export interface ImageTileOptions {fullResolution?:(geometry:PreviewGeometry)=>boolean}
import {previewPlacement,type PreviewGeometry,type PreviewState} from "./image-preview.ts";
export interface ImageTile {key:string;x:number;y:number;width:number;height:number;sampleSize:number}
export interface ImageTileRegion {x:number;y:number;width:number;height:number;offsetX:number;offsetY:number}
/** Include a source-pixel border so linear filtering can read across tile seams. */
export function imageTileRegion(tile:ImageTile,sourceWidth:number,sourceHeight:number):ImageTileRegion{
 const halo=tile.sampleSize===1?1:0;
 const x=Math.max(0,tile.x-halo),y=Math.max(0,tile.y-halo);
 const right=Math.min(sourceWidth,tile.x+tile.width+halo),bottom=Math.min(sourceHeight,tile.y+tile.height+halo);
 return {x,y,width:right-x,height:bottom-y,offsetX:tile.x-x,offsetY:tile.y-y};
}
/** Choose a source pyramid level with at least one decoded sample per screen pixel. */
export function visibleImageTiles(g:PreviewGeometry,state:PreviewState,options:ImageTileOptions={}):ImageTile[]{
 if(g.imageWidth<1||g.imageHeight<1||state.scale<=0)return [];
 const base=previewPlacement(g),unit=base.width/g.imageWidth*state.scale,pixelRatio=unit*(g.density??1);
 let sampleSize=1;while(sampleSize*2<=1/pixelRatio&&sampleSize<16384)sampleSize*=2;
 if(options.fullResolution?.(g))sampleSize=1;
 const edge=sampleSize===1?510:512*sampleSize,left=base.left+state.x+(base.width-base.width*state.scale)/2,top=base.top+state.y+(base.height-base.height*state.scale)/2;
 const x0=Math.max(0,-left/unit),y0=Math.max(0,-top/unit),x1=Math.min(g.imageWidth,(g.width-left)/unit),y1=Math.min(g.imageHeight,(g.height-top)/unit);
 if(x1<=x0||y1<=y0)return [];
 const result:ImageTile[]=[];
 for(let y=Math.floor(y0/edge)*edge;y<y1;y+=edge)for(let x=Math.floor(x0/edge)*edge;x<x1;x+=edge)result.push({key:`${sampleSize}:${x}:${y}`,x,y,width:Math.min(edge,g.imageWidth-x),height:Math.min(edge,g.imageHeight-y),sampleSize});
 return result;
}
