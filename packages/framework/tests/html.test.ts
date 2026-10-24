import {requestHtmlFragment} from '../src/html-slices.ts';
import {expect,test} from 'bun:test';
import {parseHtmlFragment,parseHtmlFragmentSteps,normalizeHtmlWhitespace} from '../src/html.ts';
test('old host returns fallback and preserves JS whitespace/surrogates',()=>{
 const old=(globalThis as any).pod;(globalThis as any).pod={};
 try{expect(parseHtmlFragment('<p>x</p>')).toBeNull();expect(normalizeHtmlWhitespace('中\ufeff \t\ud800\n🙂')).toBe('中 \ud800 🙂');}finally{(globalThis as any).pod=old;}
});
test('direct flat rows retain mixed order and optional host jobs close on cancel/failure',async()=>{
 const old=(globalThis as any).pod;let cancelled=0,appends=0;
 (globalThis as any).pod={htmlBegin:()=>1,htmlAppend:()=>{appends++;return true;},htmlNext:()=>[true,[[0,'p',['class','entry-content'],''],[1,'',[],'one'],[1,'img',['src','a>b'],''],[1,'',[],'two']]],htmlCancel:()=>cancelled++};
 try{
  expect(parseHtmlFragment('<p>one<img src="a>b">two</p>')).toEqual({tag:'body',attrs:{},children:[{tag:'p',attrs:{class:'entry-content'},children:['one',{tag:'img',attrs:{src:'a>b'},children:[]},'two']}]});expect(cancelled).toBe(1);
  const iterator=parseHtmlFragmentSteps('a'.repeat(5000));iterator.next();iterator.return(null);expect(cancelled).toBe(2);
  let publish=0;let resolve!:(value:unknown)=>void;
  const task=requestHtmlFragment('<p>x</p>',()=>publish++,{yieldTurn:()=>({result:new Promise(r=>resolve=r),cancel:()=>resolve(null)})});task.cancel();await task.done;expect(publish).toBe(0);
  (globalThis as any).pod.htmlAppend=()=>false;expect(parseHtmlFragment('x')).toBeNull();expect(cancelled).toBe(3);expect(appends).toBeGreaterThan(0);
 }finally{(globalThis as any).pod=old;}
});

import {unwrapEscapedHtml,textWorkChunks} from '../src/html';
const escape=(s:string)=>s.replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;');
test('unwrap one or two complete envelopes without decoding HTML text twice',()=>{
 const html='<p title="A &quot;B&quot;">A &amp; B <code>&lt;span&gt;</code></p>';
 expect(unwrapEscapedHtml(escape(html))).toBe(html);
 expect(unwrapEscapedHtml(escape(escape(html)))).toBe(html);
 expect(unwrapEscapedHtml(html)).toBe(html);
 for(const prose of ['literal &copy;','Use &lt;p&gt;tag&lt;/p&gt;','<pre>&lt;p&gt;text&lt;/p&gt;</pre>'])expect(unwrapEscapedHtml(prose)).toBe(prose);
});
test('work units retain every code unit, including surrogate pairs and newlines',()=>{
 const text=('a😀\n𠮷z').repeat(1000),chunks=[...textWorkChunks(text,64)];
 expect(chunks.join('')).toBe(text);expect(chunks.every(c=>c.length<=64&&!/[\uD800-\uDBFF]$/.test(c))).toBe(true);
 expect(()=>[...textWorkChunks(text,1)]).toThrow();
});
