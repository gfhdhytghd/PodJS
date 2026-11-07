/** Validate descriptors before reading values. No accessors or hidden keys. */
export function frozenDataRecord(value:unknown,keys:readonly string[]):Record<string,unknown>{
 if(!value||typeof value!=='object'||Object.getPrototypeOf(value)!==Object.prototype||!Object.isFrozen(value))throw Error('invalid_schema');
 const own=Reflect.ownKeys(value);if(own.length!==keys.length||own.some(key=>typeof key!=='string'||!keys.includes(key)))throw Error('invalid_schema');
 for(const key of keys){const d=Object.getOwnPropertyDescriptor(value,key);if(!d||!d.enumerable||!Object.hasOwn(d,'value'))throw Error('invalid_schema');}
 return value as Record<string,unknown>;
}
