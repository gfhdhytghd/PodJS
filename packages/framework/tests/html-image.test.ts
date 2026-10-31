import {expect,test} from 'bun:test';
import {htmlImageAttributes} from '../src/html.ts';
test('image hints distinguish CSS layout from source pixels and lazy placeholders',()=>{
 expect(htmlImageAttributes({src:'placeholder.gif','data-src':'portrait.jpg',width:'1',height:'1','data-w':'900','data-ratio':'1.5'})).toEqual({url:'portrait.jpg',width:900,height:1350});
 expect(htmlImageAttributes({src:'wechat.jpg',width:'100%','data-w':'900','data-ratio':'1.5'})).toEqual({url:'wechat.jpg',width:900,height:1350});
 expect(htmlImageAttributes({src:'css.jpg',style:'width: 80px; height: 120px'})).toEqual({url:'css.jpg',width:80,height:120});
 expect(htmlImageAttributes({src:'square.jpg',width:'100%',style:'aspect-ratio: 1 / 1'})).toEqual({url:'square.jpg',width:undefined,height:undefined,aspectRatio:1});
 expect(htmlImageAttributes({src:'fallback.jpg',srcset:'small.jpg 400w, large.jpg 1200w'}).url).toBe('large.jpg');
 expect(htmlImageAttributes({'data-original':'bili.jpg',src:'loading.gif'}).url).toBe('bili.jpg');
 expect(htmlImageAttributes({src:'unknown.jpg',width:'NaN','data-ratio':'-1'})).toEqual({url:'unknown.jpg',width:undefined,height:undefined});
});
