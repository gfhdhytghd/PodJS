import {expect,test} from 'bun:test';
import {parseXmlDocument,parseXmlDocumentSteps,decodeXmlEntities,requestXmlDocument} from '../src/xml.ts';
import {htmlTextPreview} from '../src/html.ts';
test('portable XML preserves descendant text, duplicate attrs and malformed errors',()=>{
 expect(parseXmlDocument('<?x?><r A="a" a="&amp;"><p>中<![CDATA[<b>]]><b>🙂</b></p><x/></r>')).toMatchObject({name:'#root',children:[{name:'r',attrs:{a:'&'},text:'中<b>🙂',children:[{name:'p',text:'中<b>🙂'},{name:'x',text:''}]}]});
 expect(()=>parseXmlDocument('<r><p>x</r>')).toThrow('Malformed XML: unexpected closing tag r');
 expect(()=>parseXmlDocument('<r/><p/>')).toThrow('expected one root element');
 expect(decodeXmlEntities('&AMP;&#12af;&#xD800;&unknown;')).toBe('&\f\ud800&unknown;');
});
test('default async portable path and cancelled publication',async()=>{
 let tree:any;await requestXmlDocument('<root>'+('<p>x</p>'.repeat(100))+'</root>',v=>tree=v).done;expect(tree.children[0].children).toHaveLength(100);
 let published=false;const task=requestXmlDocument('<root>x</root>',()=>published=true);task.cancel();await task.done;expect(published).toBe(false);
});
test('host rejection falls back exactly and closes the native slot',()=>{
 const old=(globalThis as any).pod;let closed=0;(globalThis as any).pod={xmlBegin:()=>1,xmlAppend:()=>false,xmlNext:()=>null,xmlCancel:()=>closed++};
 try{expect(parseXmlDocument('<r>x</r>').children[0].text).toBe('x');expect(closed).toBe(1);const iterator=parseXmlDocumentSteps('<r>x</r>');iterator.next();iterator.return(undefined as any);expect(closed).toBe(2);}finally{(globalThis as any).pod=old;}
});
test('HTML text prefix capability reports unsupported hosts without changing fallback policy',()=>{expect(htmlTextPreview('<p>x</p>',60)).toBeNull();expect(()=>htmlTextPreview('x',-1)).toThrow();});
