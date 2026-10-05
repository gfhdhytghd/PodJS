import { expect, test } from "bun:test";
import { speech } from "../src/services.ts";
import { __pumpPodEvents } from "../src/watch.ts";
import { POD_TARGETS } from "../src/targets.ts";
import { readFileSync } from "node:fs";

test("spectrum is separately advertised and old TTS hosts fail closed", async () => {
  const previous = (globalThis as {pod?:unknown}).pod;
  const commands:string[]=[];
  (globalThis as {pod?:unknown}).pod={capabilities:()=> '["media.tts"]',emit:(s:string)=>commands.push(s)};
  try {
    await expect(speech.setSpectrumEnabled(true)).rejects.toMatchObject({code:"unsupported"});
    await expect(speech.setSpectrumEnabled(false)).rejects.toMatchObject({code:"unsupported"});
    expect(()=>speech.onSpectrum(()=>{})).toThrow("media.tts.spectrum");
    expect(commands).toEqual([]);
    for(const id of ["android-watch","wearos-watch"] as const)expect(POD_TARGETS[id].capabilities).toContain("media.tts.spectrum");
    for(const id of ["watchos-watch","harmonyos-watch"] as const)expect(POD_TARGETS[id].capabilities).not.toContain("media.tts.spectrum");
    const source=readFileSync(new URL("../../../platforms/android/runtime/src/main/cpp/jni_bridge.cpp",import.meta.url),"utf8");
    const literal=source.match(/const char\* capabilities = ("(?:\\.|[^"\\])*");/)![1]!;
    expect(JSON.parse(JSON.parse(literal))).toEqual(POD_TARGETS["android-watch"].capabilities);
  } finally {(globalThis as {pod?:unknown}).pod=previous;}
});

test("spectrum opt-in round trips; strict events, independent listeners and removal", async () => {
  const previous=(globalThis as {pod?:unknown}).pod;
  const commands:any[]=[];let batch:unknown[]=[];let capabilities='["media.tts","media.tts.spectrum"]';
  (globalThis as {pod?:unknown}).pod={capabilities:()=>capabilities,emit:(s:string)=>commands.push(JSON.parse(s)),takeEvents:()=>{const value=JSON.stringify(batch);batch=[];return value;}};
  const pump=(...events:unknown[])=>{batch=events;__pumpPodEvents();};
  const received:unknown[]=[];const other:unknown[]=[];
  let off=()=>{},offOther=()=>{};
  try {
    off=speech.onSpectrum(event=>received.push(event));offOther=speech.onSpectrum(event=>other.push(event));
    expect(commands).toHaveLength(0);
    for(const enabled of [true,false]) {
      const result=speech.setSpectrumEnabled(enabled);
      expect(commands.at(-1)).toMatchObject({method:"tts.spectrum",args:{enabled}});
      pump({t:"service.result",id:commands.at(-1).id,ok:true,value:{enabled}});
      expect(await result).toEqual({enabled});
    }
    const frame={t:"speech.spectrum",type:"frame",source:"synthesis-pcm",utteranceId:"a",sampleRateHz:24000,levels:[0,.2,.4,.6,1]};
    const reset={...frame,type:"reset",levels:[],sampleRateHz:0};
    pump(frame,reset);
    expect(received).toHaveLength(2);expect(other).toHaveLength(2);
    expect(received[0]).toEqual({type:"frame",source:"synthesis-pcm",utteranceId:"a",sampleRateHz:24000,levels:frame.levels});
    for(const patch of [{levels:[]},{levels:[0,1]},{levels:[0,0,0,0,1.1]},{levels:[0,0,0,0,-.1]},{levels:[0,0,0,0,null]},{sampleRateHz:0},{sampleRateHz:1.5},{source:"visualizer"},{utteranceId:4},{type:"unknown"},{t:"speech.progress"}])pump({...frame,...patch});
    pump({...reset,levels:[0,0,0,0,0]});expect(received).toHaveLength(2);
    off();off();pump(frame);expect(received).toHaveLength(2);expect(other).toHaveLength(3);
    capabilities='["media.tts"]';pump(frame);expect(other).toHaveLength(3);
    expect(()=>speech.setSpectrumEnabled(1 as unknown as boolean)).toThrow("boolean");
  } finally {off();offOther();(globalThis as {pod?:unknown}).pod=previous;}
});
