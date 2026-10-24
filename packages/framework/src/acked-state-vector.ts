/** A bilateral receipt proves that BOTH owners are unchanged since an applied
 * exchange. Digests are opaque: platform-specific representations need not
 * produce the same digest. This class never advances application cursors. */
export type StateRoots = Record<string,string>;
export interface StateReceipt {localRoot:string;remoteRoot:string}
export type StateReceipts = Record<string,StateReceipt>;
const digest=/^[0-9a-f]{64}$/;
export function versionRootText(namespace:string,domain:string,epoch:string,revision:number):string {
 if(!/^[a-zA-Z0-9.-]{1,80}$/.test(namespace)||!/^[a-zA-Z][a-zA-Z0-9]{0,39}$/.test(domain)||!/^[0-9a-f]{32}$/.test(epoch)||!Number.isSafeInteger(revision)||revision<0)throw Error('Invalid domain version');
 return `${namespace}\n${domain}\n${epoch}\n${revision}`;
}
export function checkedStateRoots(value:unknown,domains:readonly string[]):StateRoots {
 if(!value||typeof value!=='object'||Array.isArray(value))throw Error('Invalid state roots');
 const roots:StateRoots={};for(const [key,root] of Object.entries(value)){
  if(!domains.includes(key)||typeof root!=='string'||!digest.test(root))throw Error('Invalid state root');roots[key]=root;
 }return roots;
}
export function checkedStateReceipts(value:unknown,domains:readonly string[]):StateReceipts {
 if(!value||typeof value!=='object'||Array.isArray(value))throw Error('Invalid state receipts');
 const receipts:StateReceipts={};for(const [key,raw] of Object.entries(value)){
  if(!domains.includes(key)||!raw||typeof raw!=='object'||Array.isArray(raw))throw Error('Invalid state receipt');
  const r=raw as StateReceipt;if(typeof r.localRoot!=='string'||typeof r.remoteRoot!=='string'||!digest.test(r.localRoot)||!digest.test(r.remoteRoot))throw Error('Invalid state receipt');
  receipts[key]={localRoot:r.localRoot,remoteRoot:r.remoteRoot};
 }return receipts;
}
export interface ProjectionVersion {encoder:number;schema:number;definition:string}
export type ProjectionVersions=Record<string,ProjectionVersion>;
export const STATE_RECEIPT_MAX_AGE_MS=604800000;
export function receiptIsCurrent(verifiedAt:number,now:number):boolean{return Number.isSafeInteger(verifiedAt)&&Number.isSafeInteger(now)&&verifiedAt>0&&verifiedAt<=now&&now-verifiedAt<STATE_RECEIPT_MAX_AGE_MS;}
export function checkedProjectionVersions(value:unknown,domains:readonly string[],roots:StateRoots={}):ProjectionVersions {
 if(!value||typeof value!=='object'||Array.isArray(value))throw Error('Invalid projection versions');
 const result:ProjectionVersions={};
 for(const [d,raw]of Object.entries(value)){
  const p=raw as ProjectionVersion;
  if(!domains.includes(d)||!p||typeof p!=='object'||Array.isArray(p)||!Number.isInteger(p.encoder)||p.encoder<1||p.encoder>2147483647||!Number.isInteger(p.schema)||p.schema<0||p.schema>2147483647||typeof p.definition!=='string'||!digest.test(p.definition))throw Error('Invalid projection version');
  result[d]={encoder:p.encoder,schema:p.schema,definition:p.definition};
 }
 if(Object.keys(roots).some(d=>!result[d]))throw Error('Missing projection version');return result;
}
export function projectionProfileText(domain:string,version:ProjectionVersion):string {
 const p=checkedProjectionVersions({[domain]:version},[domain])[domain]!;
 if(!/^[a-zA-Z][a-zA-Z0-9]{0,39}$/.test(domain))throw Error('Invalid projection domain');
 return `watchrss.ble.projection.v1\n${domain}\n${p.encoder}\n${p.schema}\n${p.definition}`;
}
export function projectionVersionRootText(domain:string,version:ProjectionVersion,epoch:string,revision:number):string {
 versionRootText('watchrss.ble.domain-version.v2',domain,epoch,revision);projectionProfileText(domain,version);
 return `watchrss.ble.domain-version.v2\n${domain}\n${version.encoder}\n${version.schema}\n${version.definition}\n${epoch}\n${revision}`;
}
export interface StatePublication {completedDomains:readonly string[];skippedDomains:readonly string[]}
export class AckedStateVector {
 private window?:{id:string;local:StateRoots;remote:StateRoots;localVersions?:ProjectionVersions;remoteVersions?:ProjectionVersions;skipped:Set<string>;completed:Set<string>;staged:StateReceipts};
 constructor(private readonly domains:readonly string[],private readonly roots:()=>Promise<StateRoots>,private readonly receipts:()=>Promise<StateReceipts>,private readonly persist:(receipts:StateReceipts,publication:StatePublication)=>Promise<void>,private readonly nonce:()=>string,private readonly options:{versions?:()=>ProjectionVersions;allowSkip?:(domain:string)=>Promise<boolean>}={}){}
 private snapshotVersions(roots:StateRoots){return this.options.versions?checkedProjectionVersions(this.options.versions(),this.domains,roots):undefined;}
 private sameVersion(a:ProjectionVersions|undefined,b:ProjectionVersions|undefined,d:string){return !this.options.versions||!!a?.[d]&&!!b?.[d]&&projectionProfileText(d,a[d])===projectionProfileText(d,b[d]);}
 async summary(remote:StateRoots,known:StateReceipts,versions?:unknown){
  this.window=undefined;
  remote=checkedStateRoots(remote,this.domains);known=checkedStateReceipts(known,this.domains);
  const remoteVersions=this.options.versions?checkedProjectionVersions(versions,this.domains,remote):undefined;
  const local=checkedStateRoots(await this.roots(),this.domains),localVersions=this.snapshotVersions(local),saved=checkedStateReceipts(await this.receipts(),this.domains),skipped=new Set<string>();
  for(const domain of this.domains){const a=saved[domain],b=known[domain];if(a&&b&&local[domain]&&remote[domain]&&a.localRoot===local[domain]&&a.remoteRoot===remote[domain]&&b.localRoot===remote[domain]&&b.remoteRoot===local[domain]&&(!this.options.allowSkip||await this.options.allowSkip(domain)))skipped.add(domain);}
  const id=this.nonce();if(!/^[\x21-\x7e]{1,128}$/.test(id))throw Error('Invalid state window ID');
  this.window={id,local,remote:{...remote},localVersions,remoteVersions,skipped,completed:new Set(),staged:{}};
  return {windowId:id,roots:local,...(localVersions?{projectionVersions:localVersions}:{}),skipDomains:[...skipped]};
 }
 completed(domain:string){if(this.domains.includes(domain))this.window?.completed.add(domain);}
 async stage(id:string,remote:StateRoots,completed:readonly string[],versions?:unknown){
  remote=checkedStateRoots(remote,this.domains);
  const remoteVersions=this.options.versions?checkedProjectionVersions(versions,this.domains,remote):undefined;
  const w=this.window;if(!w||w.id!==id)throw Error('Invalid state window');
  if(new Set(completed).size!==completed.length||completed.some(d=>!this.domains.includes(d)||!w.skipped.has(d)&&!w.completed.has(d)))throw Error('Unconfirmed state domain');
  const local=checkedStateRoots(await this.roots(),this.domains),localVersions=this.snapshotVersions(local),staged:StateReceipts={};
  for(const d of new Set([...w.skipped,...completed]))if(local[d]&&remote[d]&&w.local[d]===local[d]&&w.remote[d]===remote[d]&&this.sameVersion(w.localVersions,localVersions,d)&&this.sameVersion(w.remoteVersions,remoteVersions,d))staged[d]={localRoot:local[d]!,remoteRoot:remote[d]!};
  w.staged=staged;
  return {windowId:id,roots:local,...(localVersions?{projectionVersions:localVersions}:{}),committedDomains:Object.keys(staged),retryDomains:[...new Set([...w.skipped,...completed])].filter(d=>!staged[d])};
 }
 /** Call ONLY after enclosing session-complete applied ACK. Publication carries
  * actual domain completion so skipped receipts cannot slide their hard TTL. */
 async applied(){
  const w=this.window;this.window=undefined;if(!w)return 0;
  const local=checkedStateRoots(await this.roots(),this.domains),versions=this.snapshotVersions(local),publish:StateReceipts={};
  for(const [d,r]of Object.entries(w.staged))if(local[d]===r.localRoot&&this.sameVersion(w.localVersions,versions,d))publish[d]=r;
  if(Object.keys(publish).length)await this.persist(publish,{completedDomains:Object.keys(publish).filter(d=>!w.skipped.has(d)&&w.completed.has(d)),skippedDomains:Object.keys(publish).filter(d=>w.skipped.has(d))});
  return Object.keys(publish).length;
 }
 abort(){this.window=undefined;}
}
