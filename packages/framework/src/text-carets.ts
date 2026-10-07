/** Preserve the signal value when a row's caret positions did not change. */
export function updateCaretMap<K>(previous:ReadonlyMap<K,readonly number[]>,row:K,positions:readonly number[]|undefined):ReadonlyMap<K,readonly number[]> {
  const old=previous.get(row);
  if(positions?old?.length===positions.length&&positions.every((value,index)=>value===old[index]):!previous.has(row))return previous;
  const next=new Map(previous);
  if(positions)next.set(row,positions);else next.delete(row);
  return next;
}
