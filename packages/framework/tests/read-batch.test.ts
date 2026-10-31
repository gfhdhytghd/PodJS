import {test,expect} from 'bun:test';
import {ReadBatch} from '../src/read-batch';
test('default read batch caches only within a caller-owned transaction and drops rejected reads',async()=>{
 let value=1,reads=0;const batch=new ReadBatch<string,number>(async()=>{reads++;return value;});
 expect(await batch.get('account')).toBe(1);expect(await batch.get('account')).toBe(1);expect(reads).toBe(2);
 await batch.run(async()=>{expect(await batch.get('account')).toBe(1);value=2;expect(await batch.run(()=>batch.get('account'))).toBe(1);expect(await batch.get('other')).toBe(2);});
 expect(await batch.get('account')).toBe(2);expect(reads).toBe(5);expect(batch.active).toBe(false);
 const failing=new ReadBatch(async()=>{throw Error('fence failed');});await expect(failing.run(()=>failing.get('key'))).rejects.toThrow('fence failed');expect(failing.active).toBe(false);
});
