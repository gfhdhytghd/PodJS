type EditKind="insert"|"delete"|"replace";
interface Edit {kind:EditKind;beforeStart:number;beforeEnd:number;afterStart:number;afterEnd:number;at:number}

/** Bounded text snapshot history with adjacent-edit grouping. */
export class TextHistory {
  private undoStack:string[]=[];
  private redoStack:string[]=[];
  private lastEdit:Edit|undefined;
  value:string;

  constructor(initial:string,private readonly now=Date.now,private readonly capacity=200,private readonly groupDelayMs=500){
    if(capacity<1||groupDelayMs<0)throw new RangeError("Invalid history limits");
    this.value=initial;
  }
  get canUndo(){return this.undoStack.length>0;}
  get canRedo(){return this.redoStack.length>0;}
  record(next:string){
    if(next===this.value)return this.value;
    const edit=between(this.value,next,this.now());
    if(!this.lastEdit||!canGroup(this.lastEdit,edit,this.groupDelayMs)){
      this.undoStack.push(this.value);
      if(this.undoStack.length>this.capacity)this.undoStack.shift();
    }
    this.redoStack=[];
    this.value=next;
    this.lastEdit=edit;
    return next;
  }
  undo(){
    const previous=this.undoStack.pop();
    if(previous===undefined)return this.value;
    this.redoStack.push(this.value);
    if(this.redoStack.length>this.capacity)this.redoStack.shift();
    this.value=previous;
    this.lastEdit=undefined;
    return previous;
  }
  redo(){
    const next=this.redoStack.pop();
    if(next===undefined)return this.value;
    this.undoStack.push(this.value);
    if(this.undoStack.length>this.capacity)this.undoStack.shift();
    this.value=next;
    this.lastEdit=undefined;
    return next;
  }
}

function between(before:string,after:string,at:number):Edit{
  let prefix=0;
  while(prefix<Math.min(before.length,after.length)&&before[prefix]===after[prefix])prefix++;
  let suffix=0;
  while(suffix<Math.min(before.length,after.length)-prefix&&before[before.length-1-suffix]===after[after.length-1-suffix])suffix++;
  const beforeEnd=before.length-suffix,afterEnd=after.length-suffix;
  const removed=beforeEnd-prefix,inserted=afterEnd-prefix;
  const kind:EditKind=removed===0&&inserted>0?"insert":removed>0&&inserted===0?"delete":"replace";
  return {kind,beforeStart:prefix,beforeEnd,afterStart:prefix,afterEnd,at};
}
function canGroup(previous:Edit,next:Edit,delay:number){
  if(previous.kind!==next.kind||next.at-previous.at>delay)return false;
  return next.beforeStart<=previous.afterEnd&&next.beforeEnd>=previous.afterStart;
}
