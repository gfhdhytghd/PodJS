export function imageFitRect(width:number,height:number,sourceWidth:number,sourceHeight:number,fit:"stretch"|"cover"|"contain"="stretch") {
  if(fit==="stretch"||!Number.isFinite(sourceWidth)||!Number.isFinite(sourceHeight)||sourceWidth<=0||sourceHeight<=0)return {left:0,top:0,width,height};
  const scale=(fit==="cover"?Math.max:Math.min)(width/sourceWidth,height/sourceHeight);
  const renderedWidth=sourceWidth*scale,renderedHeight=sourceHeight*scale;
  return {left:(width-renderedWidth)/2,top:(height-renderedHeight)/2,width:renderedWidth,height:renderedHeight};
}
