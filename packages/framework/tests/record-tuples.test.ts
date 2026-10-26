import {test,expect} from 'bun:test';
import {jsonRecordTupleCodec} from '../src/record-tuples';
test('ordered JSON tuples preserve known/unknown fields and JSON values through actual serialization',()=>{
 const codec=jsonRecordTupleCodec(['articleId','sourceDeviceId','contentHash','updatedAt','deleted','bodyHash']);
 const input=JSON.parse('{"articleId":"中文路径/😀","future":{"n":123,"array":[null,true,0]},"sourceDeviceId":"watch","__proto__":{"safe":true},"deleted":false,"constructor":"value","bodyHash":"abc"}');
 const packed=codec.encode(input);expect(packed[0]).toBe(0);const actual=codec.decode(JSON.parse(JSON.stringify(packed)));
 expect(JSON.stringify(actual)).toBe(JSON.stringify(input));expect(Object.getPrototypeOf(actual)).toBe(Object.prototype);expect({}.hasOwnProperty.call(actual,'__proto__')).toBe(true);
});
test('missing fields stay absent; explicit null, empty and numeric keys retain ordinary JSON semantics',()=>{
 const codec=jsonRecordTupleCodec(['known','missing']);const input={known:null,empty:'',future:NaN,omitted:undefined,'0':'zero'};
 expect(JSON.stringify(codec.decode(JSON.parse(JSON.stringify(codec.encode(input)))))).toBe(JSON.stringify(input));
 expect(codec.decode(codec.encode({}))).not.toHaveProperty('missing');
});
test('malformed schemas, unknown numeric indexes and duplicate aliases fail closed',()=>{
 expect(()=>jsonRecordTupleCodec(['x','x'])).toThrow();const codec=jsonRecordTupleCodec(['known']);
 for(const tuple of [null,[0],[-1,0],[1,0],[.5,0],[{},0],[0,1,'known',2],['x',1,'x',2]])expect(()=>codec.decode(tuple)).toThrow();
});
