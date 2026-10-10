import {mkdirSync,rmSync,writeFileSync} from 'node:fs';
import {join} from 'node:path';
export interface AndroidSplash {background:string;icon:string;iconBackground:string;exit:{fade:boolean;scale:number;durationMs:number};timeoutMs:number}
export function validateAndroidSplash(raw:unknown):AndroidSplash {
 const bad=()=>{throw Error('Invalid android.splash');};
 if(!raw||typeof raw!=='object'||Array.isArray(raw))return bad();
 const v=raw as Record<string,any>,e=v.exit;
 if(Object.keys(v).some(k=>!['background','icon','iconBackground','exit','timeoutMs'].includes(k))||!e||typeof e!=='object'||Array.isArray(e)||Object.keys(e).some(k=>!['fade','scale','durationMs'].includes(k)))return bad();
 if(!/^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$/.test(v.background)||!/^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$/.test(v.iconBackground)||!/^@drawable\/[a-z][a-z0-9_]*$/.test(v.icon))return bad();
 if(typeof e.fade!=='boolean'||!Number.isFinite(e.scale)||e.scale<1||e.scale>2||!Number.isInteger(e.durationMs)||e.durationMs<0||e.durationMs>2000)return bad();
 const timeoutMs=v.timeoutMs??8000;if(!Number.isInteger(timeoutMs)||timeoutMs<1||timeoutMs>30000)return bad();
 return {background:v.background,icon:v.icon,iconBackground:v.iconBackground,exit:{fade:e.fade,scale:e.scale,durationMs:e.durationMs},timeoutMs};
}
export function writeAndroidSplash(out:string,splash?:AndroidSplash,theme='@style/AppTheme'):void {
 const res=join(out,'pod-android-res');rmSync(res,{recursive:true,force:true});
 if(!splash)return;
 if(!/^@style\/[A-Za-z0-9_.]+$/.test(theme)||theme==='@style/Theme.PodJS.Splash')throw Error('Invalid splash post theme');
 const v=validateAndroidSplash(splash);mkdirSync(join(res,'values'),{recursive:true});
 writeFileSync(join(res,'values/pod_splash.xml'),`<resources>
 <style name="Theme.PodJS.Splash" parent="Theme.SplashScreen.IconBackground">
  <item name="windowSplashScreenBackground">${v.background}</item>
  <item name="windowSplashScreenAnimatedIcon">${v.icon}</item>
  <item name="windowSplashScreenIconBackgroundColor">${v.iconBackground}</item>
  <item name="postSplashScreenTheme">${theme}</item>
  <item name="android:windowFullscreen">true</item>
 </style>
 <bool name="pod_splash_enabled">true</bool>
 <bool name="pod_splash_fade">${v.exit.fade}</bool>
 <string name="pod_splash_scale" translatable="false">${v.exit.scale}</string>
 <integer name="pod_splash_duration_ms">${v.exit.durationMs}</integer>
 <integer name="pod_splash_timeout_ms">${v.timeoutMs}</integer>
</resources>
`);
}
