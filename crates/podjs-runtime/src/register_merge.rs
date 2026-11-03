//! Schema-driven atomic LWW register projection. The application owns the schema.
//! Bounded, pure embedded codec call; unsupported inputs retain the guest reader.
use rquickjs::{Ctx, Function, Object, String as JsString, Value as JsValue};
use serde::Deserialize;
use serde_json::Value;
use std::{cell::RefCell, cmp::Ordering, rc::Rc};
const MAX: usize = 256 * 1024;
#[derive(Deserialize)]
struct Field { key: String, kind: String, #[serde(default)] required: bool, #[serde(default)] nonblank: bool }
#[derive(Deserialize)]
struct Term { field: Option<usize>, #[serde(default)] source: bool, #[serde(default)] invert: bool }
#[derive(Deserialize)]
struct Register { name: String, fields: Vec<usize>, tuple: Vec<Term> }
#[derive(Deserialize)]
#[serde(rename_all="camelCase")]
struct Plan {
    root: Value, fields: Vec<Field>, registers: Vec<Register>, forbidden: Vec<String>,
    source_field: usize, sources_field: usize, identity: usize, metadata: usize,
    nonzero: Vec<[usize;2]>, max_bytes: usize, output_order: Vec<usize>,
}
impl Plan {
    fn valid(&self) -> bool {
        let n=self.fields.len();
        n>0 && n<=128 && self.registers.len()<=32 && !self.registers.is_empty()
        && self.max_bytes<=MAX && self.source_field<n && self.sources_field<n && self.identity<n
        && self.output_order.iter().all(|i|*i<n) && self.metadata<self.registers.len() && self.nonzero.iter().flatten().all(|i|*i<n)
        && self.registers.iter().all(|r|r.fields.iter().all(|i|*i<n)&&r.tuple.iter().all(|t|t.source||t.field.is_some_and(|i|i<n)))
    }
    fn project(&self, text:&str) -> Option<Vec<Value>> {
        if text.len()>self.max_bytes {return None;}
        let mut root:Value=serde_json::from_str(text).ok()?;
        if !depth_ok(&root,1){return None;}
        for (k,v) in self.root.as_object()? {if root.get(k)!=Some(v){return None;}}
        let input=root.get_mut("value")?.as_object_mut()?;
        if self.forbidden.iter().any(|k|input.contains_key(k)){return None;}
        let mut out=Vec::with_capacity(self.fields.len());
        for f in &self.fields {
            let owned=input.remove(&f.key);let v=owned.as_ref();
            if f.required && v.is_none(){return None;}
            let value=match f.kind.as_str() {
                "string" => {let s=match v {None=>"",Some(v)=>v.as_str()?};
                    if f.nonblank && !s.chars().any(|c|!matches!(c, '\u{9}'..='\u{d}'|'\u{1c}'..='\u{20}'|'\u{a0}'|'\u{1680}'|'\u{2000}'..='\u{200a}'|'\u{2028}'|'\u{2029}'|'\u{202f}'|'\u{205f}'|'\u{3000}')) {return None;}
                    owned.unwrap_or_else(||Value::String(String::new()))},
                "nullableString"=>match v {None|Some(Value::Null)=>Value::Null,Some(v)=>{v.as_str()?;owned?}},
                "boolean"=>Value::Bool(match v {None=>false,Some(v)=>v.as_bool()?}),
                "uint"|"int"=>{let n=match v {None=>0.,Some(v)=>v.as_f64()?};
                    if !n.is_finite()||n.fract()!=0.||n.abs()>9007199254740991.||f.kind=="uint"&&n<0.{return None;}
                    Value::from(n as i64)},
                "unit"=>{let n=match v {None=>0.,Some(v)=>v.as_f64()?};
                    if !(0.0..=1.0).contains(&n){return None;}
                    Value::from((n*1e6+0.5).floor()/1e6)},
                "sources"=>{let obj=v?.as_object()?;
                    if obj.len()!=self.registers.len()||self.registers.iter().any(|r|obj.get(&r.name).and_then(Value::as_str).is_none()){return None;}
                    owned?},
                _=>return None,
            };
            out.push(value);
        }
        let sources=out[self.sources_field].as_object()?;
        if sources.get(&self.registers[self.metadata].name)!=Some(&out[self.source_field]){return None;}
        for pair in &self.nonzero {if out[pair[0]].as_bool()? && out[pair[1]].as_f64()?==0. {return None;}}
        Some(out)
    }
    fn merge(&self, left:&str,right:&str)->Option<String>{
        let a=self.project(left)?;let b=self.project(right)?;
        if a[self.identity]!=b[self.identity]{return None;}
        let mut out:Vec<&Value>=a.iter().collect();let mut orders=Vec::new();let mut sources=Vec::new();
        for r in &self.registers {
            let mut order=Ordering::Equal;
            for term in &r.tuple {
                let (x,y)=if term.source {(&a[self.sources_field][&r.name],&b[self.sources_field][&r.name])}else{(&a[term.field?],&b[term.field?])};
                order=compare(x,y)?;
                if term.invert {order=order.reverse();}
                if order!=Ordering::Equal {break;}
            }
            let chosen=if order==Ordering::Less {&b}else{&a};
            for &index in &r.fields {out[index]=&chosen[index];}
            sources.push((&r.name,chosen[self.sources_field].get(&r.name)?));
            orders.push(match order {Ordering::Less=>-1,Ordering::Equal=>0,Ordering::Greater=>1});
        }
        out[self.source_field]=sources[self.metadata].1;
        // Serialize in the schema's order, including the source object. JSON.parse
        // in QuickJS supplies JS numbers; the application owns wire spelling.
        let mut text=String::from("{\"orders\":");text.push_str(&serde_json::to_string(&orders).ok()?);text.push_str(",\"value\":{");
        for (i,f) in self.fields.iter().enumerate(){if i>0{text.push(',');}text.push_str(&serde_json::to_string(&f.key).ok()?);text.push(':');
            if i==self.sources_field {text.push('{');for (j,(key,v)) in sources.iter().enumerate(){if j>0{text.push(',');}text.push_str(&serde_json::to_string(key).ok()?);text.push(':');text.push_str(&serde_json::to_string(v).ok()?);}text.push('}');}
            else{text.push_str(&serde_json::to_string(&out[i]).ok()?);}
        }
        text.push_str("}}");Some(text)
    }
}
// Inputs are JSON.stringify output owned by the guest, so duplicate keys and
// lexical precision have already been resolved before this projection codec.
fn depth_ok(v:&Value, depth:usize)->bool {
    match v {Value::Array(v)=>depth<=32&&v.iter().all(|v|depth_ok(v,depth+1)),Value::Object(v)=>depth<=32&&v.values().all(|v|depth_ok(v,depth+1)),_=>true}
}
fn compare(a:&Value,b:&Value)->Option<Ordering>{
    if a==b{return Some(Ordering::Equal);}
    match (a,b){
        (Value::Null,_)=>Some(Ordering::Less),(_,Value::Null)=>Some(Ordering::Greater),
        (Value::String(a),Value::String(b))=>Some(a.encode_utf16().cmp(b.encode_utf16())),
        (Value::Bool(a),Value::Bool(b))=>Some(a.cmp(b)),
        (Value::Number(a),Value::Number(b))=>a.as_f64()?.partial_cmp(&b.as_f64()?),
        _=>None,
    }
}
fn unknown_bytes(source:&str,known:&[&str])->Option<usize>{
    let crate::json_projection::Fields(fields)=serde_json::from_str(source).ok()?;
    let mut count=0;let mut cursor=source.find('{')?+1;
    for (key,value) in fields {let at=value.get().as_ptr() as usize-source.as_ptr() as usize;let start=cursor+source.get(cursor..at)?.find('"')?;cursor=at+value.get().len();if !known.contains(&key.as_str()){count+=cursor-start;}}
    Some(count)
}
fn compose(plan:&Plan,values:&str,root:&str,basis:Option<&str>)->Option<(String,usize)>{
    use crate::{json_projection::Fields,raw_json::patch_tokens};
    let Fields(fields)=serde_json::from_str(values).ok()?;
    let changes:Vec<_>=plan.output_order.iter().map(|i|{let key=&plan.fields[*i].key;Some((key.clone(),Some(fields.iter().find(|(k,_)|k==key)?.1.get().to_owned())))}).collect::<Option<_>>()?;
    let merged=patch_tokens("{}",&changes)?;
    let Fields(root_fields)=serde_json::from_str(root).ok()?;
    let features=root_fields.iter().find(|(k,_)|k=="features")?.1.get();
    let source=if let Some(source)=basis {
        let Fields(old)=serde_json::from_str(source).ok()?;
        let old_value=old.iter().find(|(k,_)|k=="value")?.1.get();
        patch_tokens(source,&[("features".into(),Some(features.into())),("value".into(),Some(patch_tokens(old_value,&changes)?))])?
    }else{patch_tokens(root,&[("value".into(),Some(merged))])?};
    // Validate the completed source before certifying a lazy guest frame.
    plan.project(&source)?;
    let Fields(final_fields)=serde_json::from_str(&source).ok()?;
    let value=final_fields.iter().find(|(k,_)|k=="value")?.1.get();
    let mut known:Vec<&str>=plan.root.as_object()?.keys().map(String::as_str).collect();known.push("value");
    let unknown=unknown_bytes(&source,&known)?+unknown_bytes(value,&plan.fields.iter().map(|f|f.key.as_str()).collect::<Vec<_>>())?;
    Some((source,unknown))
}
pub fn mount<'js>(ctx:Ctx<'js>,pod:&Object<'js>)->rquickjs::Result<()> {
    let cache=Rc::new(RefCell::new(None::<(String,Plan)>));
    pod.set("mergeJsonRegisters",Function::new(ctx.clone(),move |ctx:Ctx<'js>,schema:String,left:JsString<'js>,right:JsString<'js>,left_raw:Option<String>,right_raw:Option<String>|->rquickjs::Result<Option<JsValue<'js>>>{
        if schema.len()>16384{return Ok(None);}
        let mut slot=cache.borrow_mut();
        if slot.as_ref().is_none_or(|(key,_)|key!=&schema){
            let Ok(plan)=serde_json::from_str::<Plan>(&schema) else{return Ok(None);};
            if !plan.valid(){return Ok(None);}*slot=Some((schema,plan));
        }
        let Ok(left)=left.to_string() else{return Ok(None);};let Ok(right)=right.to_string() else{return Ok(None);};
        let plan=&slot.as_ref().unwrap().1;
        if left_raw.as_ref().is_some_and(|s|s.len()>plan.max_bytes)||right_raw.as_ref().is_some_and(|s|s.len()>plan.max_bytes){return Ok(None);}
        let Some(result)=plan.merge(&left,&right) else{return Ok(None);};
        let output=ctx.json_parse(result)?.into_object().unwrap();
        let value:JsValue=output.get("value")?;
        let text=ctx.json_stringify(value)?.unwrap().to_string()?;
        let orders:rquickjs::Array=output.get("orders")?;
        let plan=&slot.as_ref().unwrap().1;
        let right_wins=orders.get::<i32>(plan.metadata)?<0;
        let basis=if right_wins {right_raw.as_deref()}else{left_raw.as_deref()};
        let root=if right_wins {&right}else{&left};
        let Some((source,unknown))=compose(plan,&text,root,basis) else{return Ok(None);};
        output.set("source",source)?;output.set("unknownBytes",unknown)?;
        Ok(Some(output.into_value()))
    })?)?;
    Ok(())
}
