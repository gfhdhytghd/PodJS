export interface DisclosureStorage {get<T>(key:string):T|undefined;set<T>(key:string,value:T):void}
/** Persist the initial acknowledgement; platform confirmation is per route instance. */
export class DisclosureSession {
  private initial:boolean;
  private platform:boolean;
  constructor(private readonly storage:DisclosureStorage,private readonly key:string,private readonly needsPlatform=false){
    this.initial=storage.get<boolean>(key)!==true;this.platform=needsPlatform;
  }
  pending(blocked=false):'initial'|'platform'|undefined{return this.initial?(blocked?undefined:'initial'):this.platform?'platform':undefined;}
  acknowledge(){if(this.initial){this.storage.set(this.key,true);this.initial=false;}else this.platform=false;}
  dismiss(){return false;}
}
