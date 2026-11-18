import {expect,test} from 'bun:test';
import {breakUnits,layoutUiText} from '../src/line-break.ts';

test('line units preserve CJK punctuation, surrogate pairs and joined clusters',()=>{
 for(const [source,units] of [
  ['中文，测试。',['中','文，','测','试。']],
  ['（中）文',['（中）','文']],
  ['🙂中',['🙂','中']],
  ['e\u0301 中\ufe0f',['e\u0301',' ','中\ufe0f']],
  ['👩‍👩‍👧‍👦中',['👩‍👩‍👧‍👦','中']],
 ] as [string,string[]][])expect(breakUnits(source)).toEqual(units);
 expect(layoutUiText('👩‍👩‍👧‍👦abcdefgh',{width:2,measure:text=>Array.from(text).length})).toEqual(['👩‍👩‍👧‍👦','ab','cd','ef','gh']);
});

test("bounded UI text stops measuring after the requested lines", () => {
  const body = "甲乙丙丁".repeat(900);
  let measurements = 0;
  const measure = (text: string) => { measurements++; return Array.from(text).length; };
  expect(layoutUiText(body, { width: 4, measure, maxLines: 2 })).toEqual(["甲乙丙丁", "甲乙丙丁"]);
  expect(measurements).toBeLessThan(16);
});
