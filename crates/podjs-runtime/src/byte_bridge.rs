//! Guest-lifetime, single-owner byte attachments. All entry points run on the
//! runtime thread. IO workers receive owned Java arrays, never QuickJS pointers.
use rquickjs::{Ctx, Function, Object, TypedArray};
use std::collections::BTreeMap;
use std::{cell::RefCell, rc::Rc};
const MAX_BYTES: usize = 65536;
const MAX_SLOTS: usize = 64;
#[derive(Default)]
pub(crate) struct ByteBridge {
    enabled: bool,
    next: u32,
    slots: BTreeMap<u32, Vec<u8>>,
}
impl ByteBridge {
    #[cfg(test)]
    pub(crate) fn is_empty_for_test(&self) -> bool {
        self.slots.is_empty()
    }
    fn put(&mut self, bytes: Vec<u8>) -> u32 {
        if bytes.len() > MAX_BYTES || self.slots.len() >= MAX_SLOTS {
            return 0;
        }
        let Some(id) = self.next.checked_add(1).filter(|id| *id <= i32::MAX as u32) else {
            return 0;
        };
        self.next = id;
        self.slots.insert(id, bytes);
        id
    }
    fn take(&mut self, id: u32) -> Option<Vec<u8>> {
        self.slots.remove(&id)
    }
}
// The pinned QuickJS invokes an external buffer's free callback on detach and
// again on collection, with a null pointer the second time. rquickjs 0.12.2's
// Vec constructor assumes a non-null pointer. Keep the ownership callback here
// so detaching a byte reply cannot reconstruct a Vec from null.
fn owned_array<'js>(ctx: Ctx<'js>, bytes: Vec<u8>) -> rquickjs::Result<TypedArray<'js, u8>> {
    use rquickjs::{ArrayBuffer, Value, qjs};
    use std::{ffi::c_void, mem::ManuallyDrop};
    extern "C" fn release(_: *mut qjs::JSRuntime, capacity: *mut c_void, ptr: *mut c_void) {
        if !ptr.is_null() {
            // SAFETY: the original Vec is relinquished below, and QuickJS calls
            // with its original non-null pointer once; detach clears that pointer.
            unsafe {
                drop(Vec::from_raw_parts(
                    ptr.cast::<u8>(),
                    capacity as usize,
                    capacity as usize,
                ));
            }
        }
    }
    let mut bytes = ManuallyDrop::new(bytes);
    let ptr = bytes.as_mut_ptr();
    let capacity = bytes.capacity();
    // SAFETY: the moved Vec exclusively owns the buffer until release. The
    // failure path in this pinned engine does not invoke the buffer callback.
    let raw = unsafe {
        qjs::JS_NewArrayBuffer(
            ctx.as_raw().as_ptr(),
            ptr,
            bytes.len() as _,
            Some(release),
            capacity as *mut c_void,
            false,
        )
    };
    if unsafe { qjs::JS_IsException(raw) } {
        unsafe {
            drop(Vec::from_raw_parts(ptr, capacity, capacity));
        }
        return Err(rquickjs::Error::Exception);
    }
    let value = unsafe { Value::from_raw(ctx, raw) };
    let buffer = ArrayBuffer::from_value(value).ok_or(rquickjs::Error::Exception)?;
    TypedArray::from_arraybuffer(buffer)
}
pub(crate) fn install<'js>(
    ctx: &Ctx<'js>,
    ns: &Object<'js>,
    bridge: Rc<RefCell<crate::PodBridge>>,
) -> rquickjs::Result<()> {
    let b = bridge.clone();
    ns.set(
        "byteBridgeVersion",
        Function::new(
            ctx.clone(),
            move || if b.borrow().bytes.enabled { 1 } else { 0 },
        )?,
    )?;
    let b = bridge.clone();
    ns.set(
        "putBytes",
        Function::new(ctx.clone(), move |input: TypedArray<'_, u8>| {
            input
                .as_bytes()
                .filter(|v| v.len() <= MAX_BYTES)
                .map_or(0, |v| b.borrow_mut().bytes.put(v.to_vec()))
        })?,
    )?;
    let b = bridge.clone();
    ns.set(
        "takeBytes",
        Function::new(ctx.clone(), move |ctx: Ctx<'js>, id: u32| {
            b.borrow_mut()
                .bytes
                .take(id)
                .map(|bytes| owned_array(ctx, bytes))
                .transpose()
        })?,
    )?;
    ns.set(
        "dropBytes",
        Function::new(ctx.clone(), move |id: u32| {
            bridge.borrow_mut().bytes.take(id);
        })?,
    )?;
    Ok(())
}
/// # Safety
/// Runtime and output are live and exclusively borrowed on the runtime thread.
/// Pointer lasts until the next byte bridge mutation. 0 denotes unknown handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_runtime_bytes_peek(
    runtime: *mut crate::PodRuntime,
    id: u32,
    length: *mut usize,
) -> *const u8 {
    let Some(r) = (unsafe { runtime.as_ref() }) else {
        return std::ptr::null();
    };
    if length.is_null() {
        return std::ptr::null();
    }
    let b = r.bridge.borrow();
    match b.bytes.slots.get(&id) {
        Some(bytes) => {
            unsafe { *length = bytes.len() };
            bytes.as_ptr()
        }
        None => std::ptr::null(),
    }
}
/// # Safety
/// Runtime is live and exclusively borrowed on its owner thread.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_runtime_bytes_drop(runtime: *mut crate::PodRuntime, id: u32) {
    if let Some(r) = unsafe { runtime.as_ref() } {
        r.bridge.borrow_mut().bytes.take(id);
    }
}
/// # Safety
/// Runtime is exclusively borrowed on its owner thread; input is readable for length.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_runtime_bytes_put(
    runtime: *mut crate::PodRuntime,
    input: *const u8,
    length: usize,
) -> u32 {
    let Some(r) = (unsafe { runtime.as_ref() }) else {
        return 0;
    };
    if input.is_null() || length > MAX_BYTES {
        return 0;
    }
    r.bridge
        .borrow_mut()
        .bytes
        .put(unsafe { std::slice::from_raw_parts(input, length) }.to_vec())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn boundaries_ownership_capacity_and_long_loop() {
        let mut b = ByteBridge::default();
        for size in [0, 1, 65535, 65536] {
            let bytes = vec![255; size];
            let h = b.put(bytes.clone());
            assert_ne!(h, 0);
            assert_eq!(b.take(h).unwrap(), bytes);
            assert!(b.take(h).is_none());
        }
        assert_eq!(b.put(vec![0; 65537]), 0);
        for _ in 0..64 {
            assert_ne!(b.put(vec![0; 65536]), 0);
        }
        assert_eq!(b.put(vec![]), 0);
        b.slots.clear();
        for _ in 0..100000 {
            let h = b.put(vec![1; 65536]);
            assert_eq!(b.take(h).unwrap().len(), 65536);
            assert!(b.slots.is_empty());
        }
        b.next = u32::MAX;
        assert_eq!(b.put(vec![]), 0);
    }
    #[test]
    fn quickjs_views_detached_and_ownership() {
        let rt = rquickjs::Runtime::new().unwrap();
        let c = rquickjs::Context::full(&rt).unwrap();
        let b = Rc::new(RefCell::new(crate::PodBridge::default()));
        c.with(|ctx|{let ns=Object::new(ctx.clone()).unwrap();install(&ctx,&ns,b.clone()).unwrap();ctx.globals().set("pod",ns).unwrap();
   assert_eq!(ctx.eval::<u32,_>("pod.byteBridgeVersion()").unwrap(),0);
   b.borrow_mut().bytes.enabled=true;
   assert_eq!(ctx.eval::<u32,_>("pod.byteBridgeVersion()").unwrap(),1);
   ctx.eval::<(),_>("let a=new Uint8Array([9,0,128,255,8]);let h=pod.putBytes(a.subarray(1,4));a.fill(7);let v=pod.takeBytes(h);if(v.length!==3||v[0]!==0||v[1]!==128||v[2]!==255||pod.takeBytes(h)!=null)throw Error('ownership');if(pod.putBytes(new Uint8Array(65537))!==0)throw Error('limit');let x=pod.putBytes(new Uint8Array());if(pod.takeBytes(x).length!==0)throw Error('empty');").unwrap();
   assert!(ctx.eval::<(),_>("pod.putBytes('invalid')").is_err());
   let mut buffer=rquickjs::ArrayBuffer::new_copy(ctx.clone(),vec![1u8;4]).unwrap();
   ctx.globals().set("detached",buffer.clone()).unwrap();
   ctx.eval::<(),_>("globalThis.detachedView=new Uint8Array(detached)").unwrap();buffer.detach();
   assert_eq!(ctx.eval::<u32,_>("pod.putBytes(detachedView)").unwrap(),0);
   for size in [0,1,65536] {
      ctx.eval::<(),_>(format!("globalThis.output=pod.takeBytes(pod.putBytes(new Uint8Array({size})));globalThis.outputBuffer=output.buffer;")).unwrap();
      let mut buffer:rquickjs::ArrayBuffer=ctx.globals().get("outputBuffer").unwrap();buffer.detach();buffer.detach();
   }

  });
        assert!(b.borrow().bytes.slots.is_empty());
    }
}

/// # Safety
/// Called by hosts implementing the binary service attachment contract, on the owner thread.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_runtime_bytes_enable(runtime: *mut crate::PodRuntime) {
    if let Some(r) = unsafe { runtime.as_ref() } {
        r.bridge.borrow_mut().bytes.enabled = true;
    }
}
