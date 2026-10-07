export type ScrollSink = (offset:number)=>void;
/** Position and speed use the same unit; the reader supplies logical pixels. */
export class AutoScrollController {
  private running=false;
  private background=false;
  private offset=0;
  private maximum=Infinity;
  constructor(private speed=1, private readonly sink:ScrollSink=()=>{}) {}
  setOffset(offset:number){this.offset=Math.min(this.maximum,Math.max(0,Number.isFinite(offset)?offset:0));this.sink(this.offset);}
  setBounds(maximum:number){this.maximum=Math.max(0,Number.isFinite(maximum)?maximum:0);this.setOffset(this.offset);if(this.offset>=this.maximum)this.pause();}
  setSpeed(speed:number){this.speed=Number.isFinite(speed)?Math.max(0,speed):0;}
  getOffset(){return this.offset;}
  start(){this.running=this.offset<this.maximum;}
  pause(){this.running=false;}
  setBackground(value:boolean){this.background=value;}
  tick(elapsedMs:number){
    if(!this.running||this.background||!Number.isFinite(elapsedMs))return;
    this.setOffset(this.offset+Math.max(0,elapsedMs)/1000*this.speed);
    if(this.offset>=this.maximum)this.pause();
  }
  isRunning(){return this.running&&!this.background;}
  isRequested(){return this.running;}
}
