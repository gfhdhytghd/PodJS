/** Memoize reads only inside a caller-owned read transaction. Never retain a
 * value across transactions. The caller serializes transactions and writers. */
export class ReadBatch<K,V> {
 private values?:Map<K,Promise<V>>;
 constructor(private load:(key:K)=>Promise<V>){}
 get active(){return this.values!==undefined;}
 get(key:K):Promise<V>{if(!this.values)return this.load(key);let value=this.values.get(key);if(!value){value=this.load(key);this.values.set(key,value);}return value;}
 async run<T>(action:()=>Promise<T>):Promise<T>{
  if(this.values)return action();
  this.values=new Map();try{return await action();}finally{this.values.clear();this.values=undefined;}
 }
}
