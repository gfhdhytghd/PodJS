import {mkdirSync,readFileSync,writeFileSync,readdirSync,unlinkSync} from 'node:fs';
import {join} from 'node:path';
export interface DebugExternalConfig { activities?:{name:string;action:string}[];receiver?:{name:string;actions:string[]};provider?:{name:string;authority:string} }
const qualified=/^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+$/;
export function validateDebugExternal(raw:unknown):DebugExternalConfig {
 if(!raw||typeof raw!=='object'||Array.isArray(raw))throw Error('android.debugExternal must be an object');
 const c=raw as DebugExternalConfig;
 const valid=(s:unknown)=>typeof s==='string'&&qualified.test(s);
 if(c.activities&&(!Array.isArray(c.activities)||c.activities.length>16||c.activities.some(a=>!valid(a.name)||!valid(a.action))))throw Error('invalid debug activities');
 if(c.receiver&&(!valid(c.receiver.name)||!Array.isArray(c.receiver.actions)||!c.receiver.actions.length||c.receiver.actions.length>32||c.receiver.actions.some(a=>!valid(a))))throw Error('invalid debug receiver');
 if(c.provider&&(!valid(c.provider.name)||!valid(c.provider.authority)))throw Error('invalid debug provider');
 const names=[...(c.activities??[]).map(a=>a.name),c.receiver?.name,c.provider?.name].filter(Boolean);
 if(new Set(names.map(name=>name!.split('.').pop())).size!==names.length||names.some(name=>name!.startsWith('dev.podjs.debug.')||['DebugEntry','PodDebugExternal'].includes(name!.split('.').pop()!)))throw Error('duplicate debug component');
 return c;
}
/** Separate source set: these classes and components never enter an ordinary release. */
export function writeDebugExternal(root:string,config:DebugExternalConfig|undefined,mainActivity:string):void {
 mkdirSync(join(root,'java'),{recursive:true});
 for(const name of readdirSync(join(root,'java')))if(name.endsWith('.java'))unlinkSync(join(root,'java',name));
 const c=config?validateDebugExternal(config):{};
 const components:string[]=[];
 const java=(name:string,source:string)=>{const i=name.lastIndexOf('.');writeFileSync(join(root,'java',name.slice(i+1)+'.java'),`package ${name.slice(0,i)};\n${source}\n`);};
 if(config){
  writeFileSync(join(root,'java','PodDebugExternal.java'),readFileSync(join(import.meta.dir,'../templates/debug-external/PodDebugExternal.java')));
  java('dev.podjs.debug.DebugEntry',`public class DebugEntry extends android.app.Activity { protected void onCreate(android.os.Bundle state){super.onCreate(state);try{android.content.pm.ActivityInfo info=getPackageManager().getActivityInfo(getIntent().getComponent(),android.content.pm.PackageManager.GET_META_DATA);String action=info.metaData.getString("pod.debug.action");dev.podjs.debug.PodDebugExternal.intent(this,action,getIntent(),null);startActivity(new android.content.Intent(this,Class.forName(${JSON.stringify(mainActivity)})).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP|android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));}catch(Exception ignored){}finish();}}`);
  components.push('<activity android:name="dev.podjs.debug.DebugEntry" android:exported="false"/>');
  for(const a of c.activities??[])components.push(`<activity-alias android:name="${a.name}" android:targetActivity="dev.podjs.debug.DebugEntry" android:exported="true"><meta-data android:name="pod.debug.action" android:value="${a.action}"/><intent-filter><action android:name="${a.action}"/><category android:name="android.intent.category.DEFAULT"/></intent-filter></activity-alias>`);
  if(c.receiver){const r=c.receiver;java(r.name,`public class ${r.name.split('.').pop()} extends android.content.BroadcastReceiver {public void onReceive(android.content.Context c,android.content.Intent i){if(i==null||!java.util.Arrays.asList(${r.actions.map(a=>JSON.stringify(a)).join(',')}).contains(i.getAction()))return;dev.podjs.debug.PodDebugExternal.intent(c,i.getAction(),i,goAsync());}}`);components.push(`<receiver android:name="${r.name}" android:exported="true"/>`);}
  if(c.provider){const p=c.provider;java(p.name,`public class ${p.name.split('.').pop()} extends dev.podjs.debug.PodDebugExternal.Provider {}`);components.push(`<provider android:name="${p.name}" android:authorities="${p.authority}" android:exported="true"/>`);}
 }
 writeFileSync(join(root,'AndroidManifest.xml'),`<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application>${components.join('')}</application></manifest>\n`);
}
