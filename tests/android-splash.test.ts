import {expect,test} from 'bun:test';
import {mkdtempSync,readFileSync,existsSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {validateAndroidSplash,writeAndroidSplash} from '../packages/cli/src/android-splash.ts';
const config={background:'#000000',icon:'@drawable/sample_icon',iconBackground:'#000000',exit:{fade:true,scale:1.12,durationMs:80}};
test('splash configuration generates the AndroidX/system theme, post theme and bounded timeout resources',()=>{
 const out=mkdtempSync(join(tmpdir(),'pod-splash-')),splash=validateAndroidSplash(config);expect(splash.timeoutMs).toBe(8000);
 writeAndroidSplash(out,splash,'@style/Demo');const xml=readFileSync(join(out,'pod-android-res/values/pod_splash.xml'),'utf8');
 for(const value of ['parent="Theme.SplashScreen.IconBackground"','windowSplashScreenBackground">#000000','windowSplashScreenAnimatedIcon">@drawable/sample_icon','postSplashScreenTheme">@style/Demo','pod_splash_timeout_ms">8000','pod_splash_duration_ms">80','pod_splash_scale" translatable="false">1.12'])expect(xml).toContain(value);
 writeAndroidSplash(out);expect(existsSync(join(out,'pod-android-res'))).toBe(false);
});
test('splash XML/resource injection and unbounded holds are rejected',()=>{
 for(const bad of [{...config,background:'<x>'},{...config,icon:'@drawable/icon<item>'},{...config,iconBackground:'red'},{...config,timeoutMs:30001},{...config,timeoutMs:0},{...config,exit:{...config.exit,durationMs:Infinity}},{...config,exit:{...config.exit,scale:3}},{...config,exit:{...config.exit,fade:1}},{...config,other:true}])expect(()=>validateAndroidSplash(bad)).toThrow('android.splash');
 const out=mkdtempSync(join(tmpdir(),'pod-splash-'));expect(()=>writeAndroidSplash(out,validateAndroidSplash(config),'@style/Theme.PodJS.Splash')).toThrow();
});
