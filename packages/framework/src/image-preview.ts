export interface PreviewState {scale:number;x:number;y:number}
export interface PreviewGeometry {width:number;height:number;imageWidth:number;imageHeight:number;density?:number}
export function previewBase(g:PreviewGeometry){const fit=Math.min(g.width/Math.max(1,g.imageWidth),g.height/Math.max(1,g.imageHeight));return {width:g.imageWidth*fit,height:g.imageHeight*fit};}
export function previewMaxScale(g:PreviewGeometry){const b=previewBase(g);return b.width>0&&b.height>0?Math.max(1,g.width*4/b.width,g.height*4/b.height,g.imageWidth*4/b.width/(g.density??1),g.imageHeight*4/b.height/(g.density??1)):1;}
export function previewRange(g:PreviewGeometry,scale:number){const b=previewBase(g);return {x:Math.max(0,(b.width*scale-g.width)/2),y:Math.max(0,(b.height*scale-g.height)/2)};}
export function clampPreview(g:PreviewGeometry,s:PreviewState):PreviewState {const scale=Math.max(.5,Math.min(previewMaxScale(g),Number.isFinite(s.scale)?s.scale:.5)),r=previewRange(g,scale);return {scale,x:Math.max(-r.x,Math.min(r.x,Number.isFinite(s.x)?s.x:0)),y:Math.max(-r.y,Math.min(r.y,Number.isFinite(s.y)?s.y:0))};}
export function zoomPreview(g:PreviewGeometry,s:PreviewState,scale:number,x:number,y:number):PreviewState {const next=Math.max(.5,Math.min(previewMaxScale(g),scale)),cx=x-g.width/2,cy=y-g.height/2;return clampPreview(g,{scale:next,x:cx-(cx-s.x)*next/s.scale,y:cy-(cy-s.y)*next/s.scale});}
export function nextPreviewScale(g:PreviewGeometry,current:number){const max=previewMaxScale(g),first=Math.min(2,max),second=Math.min(4,max);return current<first-.05?first:current<second-.05?second:1;}
export function previewAllowsBack(g:PreviewGeometry,s:PreviewState){const range=previewRange(g,s.scale).x;return range<=0||s.x>=range-1/(g.density??1);}
/** Compatibility gesture starts a swipe-back candidate only in the left 65%. */
export function previewCanStartBack(g:PreviewGeometry,s:PreviewState,startX:number,dx:number,dy:number){return startX<=g.width*.65&&dx>0&&Math.abs(dx)>Math.abs(dy)&&previewAllowsBack(g,s);}

/** The centroid-anchored pinch update, including optional centroid pan. */
export function pinchPreviewStep(g:PreviewGeometry,s:PreviewState,zoomChange:number,cx:number,cy:number,panX:number,panY:number):PreviewState {
 const scale=Math.max(.5,Math.min(previewMaxScale(g),s.scale*zoomChange));
 const ratio=scale/s.scale,centerX=g.width/2,centerY=g.height/2;
 return clampPreview(g,{scale,x:s.x+panX+(cx-centerX-s.x)*(1-ratio),y:s.y+panY+(cy-centerY-s.y)*(1-ratio)});
}

/** Compatibility FloatExponentialDecaySpec with frictionMultiplier=13.6. */
export function previewScaleDecayStep(g:PreviewGeometry,s:PreviewState,velocity:number,dt:number){
 const factor=Math.exp(-4.2*13.6*dt),next=clampPreview(g,{...s,scale:s.scale+velocity*(1-factor)/(4.2*13.6)});
 return {state:next,velocity:next.scale===s.scale?0:velocity*factor};
}

/** Compatibility pan decay uses frictionMultiplier=0.9 on each axis. */
export function previewPanDecayStep(g:PreviewGeometry,s:PreviewState,vx:number,vy:number,dt:number){
 const factor=Math.exp(-4.2*.9*dt),step=(1-factor)/(4.2*.9);
 const next=clampPreview(g,{...s,x:s.x+vx*step,y:s.y+vy*step});
 return {state:next,vx:next.x===s.x?0:vx*factor,vy:next.y===s.y?0:vy*factor};
}

/** Compatibility centers its integer-pixel measured image, then applies the transform. */
export function previewPlacement(g:PreviewGeometry){const b=previewBase(g),d=g.density??1,width=Math.round(b.width*d)/d,height=Math.round(b.height*d)/d;return {width,height,left:Math.round((g.width-width)*d/2)/d,top:Math.round((g.height-height)*d/2)/d};}
