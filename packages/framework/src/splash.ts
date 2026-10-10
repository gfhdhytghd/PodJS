import {hostOperation,HostServiceError} from './services.ts';
function ownerId(owner:string):string {
 if(typeof owner!=='string'||!/^[A-Za-z0-9_.:-]{1,128}$/.test(owner))throw new HostServiceError('invalid_argument','Invalid splash owner');
 return owner;
}
/** Activity-scoped, idempotent holds. They never extend the host's absolute timeout.
 * No configuration/capability returns unsupported. release cannot resurrect a splash. */
export function hold(owner='guest'):Promise<void>{return hostOperation<void>('splash.hold',{owner:ownerId(owner)}).result;}
export function release(owner='guest'):Promise<void>{return hostOperation<void>('splash.release',{owner:ownerId(owner)}).result;}
