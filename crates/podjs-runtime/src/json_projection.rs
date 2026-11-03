//! JSON field projection. Raw subtrees preserve ECMAScript numeric/string
//! spelling; the guest parses only selected values. No domain fields live here.
use rquickjs::{Array, Ctx, Function, Object, String as JsString};
use serde::{
    de::{MapAccess, Visitor},
    Deserialize, Deserializer,
};
use serde_json::{value::RawValue, Value};
use std::{
    cell::RefCell,
    collections::{HashMap, VecDeque},
    fmt,
    rc::Rc,
};
const LIMIT: usize = 8 * 1024 * 1024;
pub(crate) struct Fields<'a>(pub(crate) Vec<(String, &'a RawValue)>);
impl<'de> Deserialize<'de> for Fields<'de> {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        struct V;
        impl<'de> Visitor<'de> for V {
            type Value = Fields<'de>;
            fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
                f.write_str("object")
            }
            fn visit_map<M: MapAccess<'de>>(self, mut m: M) -> Result<Self::Value, M::Error> {
                let mut out: Vec<(String, &RawValue)> = Vec::new();
                while let Some((k, v)) = m.next_entry::<String, &RawValue>()? {
                    if let Some(old) = out.iter_mut().find(|(name, _)| name == &k) {
                        old.1 = v;
                    } else {
                        out.push((k, v));
                    }
                }
                Ok(Fields(out))
            }
        }
        d.deserialize_map(V)
    }
}
fn project(raw: &str, selection: &Value, depth: usize) -> Option<String> {
    if depth > 32 {
        return None;
    }
    match selection {
        Value::Null => Some(raw.into()),
        Value::Object(spec) if raw.starts_with('{') => {
            let Fields(fields) = serde_json::from_str(raw).ok()?;
            let mut out = String::from("{");
            let mut first = true;
            for (key, value) in fields {
                if let Some(child) = spec.get(&key) {
                    if !first {
                        out.push(',');
                    }
                    first = false;
                    out.push_str(&serde_json::to_string(&key).ok()?);
                    out.push(':');
                    out.push_str(&project(value.get(), child, depth + 1)?);
                }
            }
            out.push('}');
            Some(out)
        }
        Value::Array(spec) if spec.len() == 1 && raw.starts_with('[') => {
            let rows: Vec<&RawValue> = serde_json::from_str(raw).ok()?;
            let mut out = String::from("[");
            for (i, row) in rows.iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }
                out.push_str(&project(row.get(), &spec[0], depth + 1)?);
            }
            out.push(']');
            Some(out)
        }
        Value::Object(_) | Value::Array(_) => Some(raw.into()),
        _ => None,
    }
}
struct Op {
    key: Rc<String>,
    index: i32,
    start: usize,
    end: usize,
    selection: Rc<Value>,
}
struct Job {
    source: String,
    selection: Value,
    ready: bool,
    initialized: bool,
    ops: VecDeque<Op>,
}
impl Job {
    fn initialize(&mut self) -> Option<()> {
        let spec = self.selection.as_object()?;
        // RawValue validation includes discarded fields and trailing text. Malformed
        // or unsupported strings fall back to the guest's exact JSON.parse behavior.
        let _: &RawValue = serde_json::from_str(&self.source).ok()?;
        let Fields(fields) = serde_json::from_str(&self.source).ok()?;
        for (key, value) in fields {
            if let Some(child) = spec.get(&key) {
                let key = Rc::new(key);
                let range = |raw: &RawValue| {
                    let start = raw.get().as_ptr() as usize - self.source.as_ptr() as usize;
                    (start, start + raw.get().len())
                };
                if let Value::Array(item) = child {
                    if item.len() != 1 {
                        return None;
                    }
                    if value.get().starts_with('[') {
                        self.ops.push_back(Op {
                            key: key.clone(),
                            index: -2,
                            start: 0,
                            end: 0,
                            selection: Rc::new(Value::Null),
                        });
                        let rows: Vec<&RawValue> = serde_json::from_str(value.get()).ok()?;
                        if self.ops.len() + rows.len() > 65536 {
                            return None;
                        }
                        let item = Rc::new(item[0].clone());
                        for (i, row) in rows.into_iter().enumerate() {
                            let (start, end) = range(row);
                            self.ops.push_back(Op {
                                key: key.clone(),
                                index: i as i32,
                                start,
                                end,
                                selection: item.clone(),
                            });
                        }
                        continue;
                    }
                }
                let (start, end) = range(value);
                self.ops.push_back(Op {
                    key,
                    index: -1,
                    start,
                    end,
                    selection: Rc::new(child.clone()),
                });
            }
        }
        if self.ops.len() > 65536 {
            return None;
        }
        self.initialized = true;
        Some(())
    }
    fn next(&mut self) -> Option<(i32, String, i32, String)> {
        if !self.ready {
            return None;
        }
        if !self.initialized {
            self.initialize()?;
            return Some((0, String::new(), -1, String::new()));
        }
        let Some(op) = self.ops.pop_front() else {
            return Some((2, String::new(), -1, String::new()));
        };
        let json = if op.index == -2 {
            String::new()
        } else {
            project(&self.source[op.start..op.end], &op.selection, 0)?
        };
        Some((1, (*op.key).clone(), op.index, json))
    }
}
pub fn mount<'js>(ctx: Ctx<'js>, pod: &Object<'js>) -> rquickjs::Result<()> {
    let jobs = Rc::new(RefCell::new(HashMap::<u32, Job>::new()));
    let counter = Rc::new(RefCell::new(0u32));
    let begin = jobs.clone();
    pod.set(
        "jsonProjectionBegin",
        Function::new(ctx.clone(), move |selection: String| -> Option<u32> {
            let selection: Value = serde_json::from_str(&selection).ok()?;
            if !selection.is_object()
                || selection.to_string().len() > 16384
                || begin.borrow().len() >= 4
            {
                return None;
            }
            let mut n = counter.borrow_mut();
            *n = n.wrapping_add(1);
            let id = *n;
            begin.borrow_mut().insert(
                id,
                Job {
                    source: String::new(),
                    selection,
                    ready: false,
                    initialized: false,
                    ops: VecDeque::new(),
                },
            );
            Some(id)
        })?,
    )?;
    let append = jobs.clone();
    pod.set(
        "jsonProjectionAppend",
        Function::new(
            ctx.clone(),
            move |id: u32, part: JsString, last: bool| -> bool {
                let Ok(part) = part.to_string() else {
                    return false;
                };
                if part.len() > 16384 {
                    return false;
                }
                let mut jobs = append.borrow_mut();
                let Some(job) = jobs.get_mut(&id) else {
                    return false;
                };
                if job.ready || job.source.len() + part.len() > LIMIT {
                    jobs.remove(&id);
                    return false;
                }
                job.source.push_str(&part);
                job.ready = last;
                true
            },
        )?,
    )?;
    let next = jobs.clone();
    pod.set(
        "jsonProjectionNext",
        Function::new(
            ctx.clone(),
            move |ctx: Ctx<'js>, id: u32| -> rquickjs::Result<Option<Array<'js>>> {
                let mut jobs = next.borrow_mut();
                let result = jobs.get_mut(&id).and_then(|j| j.next());
                if result.as_ref().is_none_or(|r| r.0 == 2) {
                    jobs.remove(&id);
                }
                let Some((status, key, index, json)) = result else {
                    return Ok(None);
                };
                let row = Array::new(ctx)?;
                row.set(0, status)?;
                row.set(1, key)?;
                row.set(2, index)?;
                row.set(3, json)?;
                Ok(Some(row))
            },
        )?,
    )?;
    pod.set(
        "jsonProjectionCancel",
        Function::new(ctx, move |id: u32| {
            jobs.borrow_mut().remove(&id);
        })?,
    )?;
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn raw_numbers_duplicates_and_strings() {
        let s = serde_json::json!({"rows":[{"n":null,"s":null}],"x":null});
        let raw = r#"{"discard":{"big":[1,2]},"rows":[{"n":1e400,"s":"\ud800","drop":0,"n":-0}],"x":999999999999999999999999999999999}"#;
        assert_eq!(
            project(raw, &s, 0).unwrap(),
            r#"{"rows":[{"n":-0,"s":"\ud800"}],"x":999999999999999999999999999999999}"#
        );
    }
    #[test]
    fn jobs_stream_array_and_preserve_nonarrays() {
        let mut j = Job {
            source: r#"{"rows":[{"n":1},{"n":2}],"x":false}"#.into(),
            selection: serde_json::json!({"rows":[{"n":null}],"x":null}),
            ready: true,
            initialized: false,
            ops: VecDeque::new(),
        };
        assert_eq!(j.next().unwrap().0, 0);
        assert_eq!(j.next().unwrap().2, -2);
        assert_eq!(j.next().unwrap().3, r#"{"n":1}"#);
        assert_eq!(j.next().unwrap().3, r#"{"n":2}"#);
        assert_eq!(j.next().unwrap().3, "false");
        assert_eq!(j.next().unwrap().0, 2);
    }
}
