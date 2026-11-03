//! Bounded raw JSON spans. Preserve original UTF-16 offsets and unknown tokens.
//! Failure/oversize returns None so the portable scanner owns error semantics.
use rquickjs::{Array, Ctx, Object, String as JsString};
use std::collections::HashSet;
const MAX_BYTES: usize = 256 * 1024;
const MAX_NODES: usize = 8192;
/// Count one bounded strict UTF-8 chunk without returning/copying a byte array.
pub fn utf8_length(source: JsString<'_>) -> rquickjs::Result<Option<usize>> {
    let raw = source.to_cstring()?;
    if raw.len() > MAX_BYTES {
        return Ok(None);
    }
    let bytes = unsafe { std::slice::from_raw_parts(raw.as_ptr().cast::<u8>(), raw.len()) };
    Ok(std::str::from_utf8(bytes).ok().map(str::len))
}
struct Node {
    start: usize,
    end: usize,
    kind: &'static str,
    props: Vec<(String, usize, Node)>,
    items: Vec<Node>,
}
struct Scan<'a> {
    s: &'a str,
    at: usize,
    utf16: usize,
    depth: usize,
    nodes: usize,
}
impl Scan<'_> {
    fn byte(&self) -> Option<u8> {
        self.s.as_bytes().get(self.at).copied()
    }
    fn ascii(&mut self, n: usize) {
        self.at += n;
        self.utf16 += n;
    }
    fn ws(&mut self) {
        while matches!(self.byte(), Some(b' ' | b'\t' | b'\r' | b'\n')) {
            self.ascii(1);
        }
    }
    fn string(&mut self, key: bool) -> Option<String> {
        let start = self.at;
        if self.byte() != Some(b'"') {
            return None;
        }
        self.ascii(1);
        loop {
            match self.byte()? {
                b'"' => {
                    self.ascii(1);
                    break;
                }
                0..=31 => return None,
                b'\\' => {
                    self.ascii(1);
                    match self.byte()? {
                        b'"' | b'\\' | b'/' | b'b' | b'f' | b'n' | b'r' | b't' => self.ascii(1),
                        b'u' => {
                            self.ascii(1);
                            let raw = self.s.get(self.at..self.at + 4)?;
                            if !raw.bytes().all(|b| b.is_ascii_hexdigit()) {
                                return None;
                            }
                            let n = u16::from_str_radix(raw, 16).ok()?;
                            self.ascii(4);
                            if (0xd800..=0xdbff).contains(&n) {
                                if self.s.get(self.at..self.at + 2)? != "\\u" {
                                    return None;
                                }
                                self.ascii(2);
                                let raw = self.s.get(self.at..self.at + 4)?;
                                if !raw.bytes().all(|b| b.is_ascii_hexdigit()) {
                                    return None;
                                }
                                let low = u16::from_str_radix(raw, 16).ok()?;
                                if !(0xdc00..=0xdfff).contains(&low) {
                                    return None;
                                }
                                self.ascii(4);
                            } else if (0xdc00..=0xdfff).contains(&n) {
                                return None;
                            }
                        }
                        _ => return None,
                    }
                }
                32..=127 => self.ascii(1),
                _ => {
                    let c = self.s[self.at..].chars().next()?;
                    self.at += c.len_utf8();
                    self.utf16 += c.len_utf16();
                }
            }
        }
        if key {
            serde_json::from_str(&self.s[start..self.at]).ok()
        } else {
            Some(String::new())
        }
    }
    fn value(&mut self, depth: usize) -> Option<Node> {
        self.nodes += 1;
        if self.nodes > MAX_NODES {
            return None;
        }
        self.ws();
        let start = self.utf16;
        let mut n = Node {
            start,
            end: 0,
            kind: "scalar",
            props: vec![],
            items: vec![],
        };
        match self.byte()? {
            b'{' => {
                if depth > self.depth {
                    return None;
                }
                n.kind = "object";
                self.ascii(1);
                self.ws();
                let mut seen = HashSet::new();
                if self.byte() != Some(b'}') {
                    loop {
                        self.ws();
                        let at = self.utf16;
                        let key = self.string(true)?;
                        if !seen.insert(key.clone()) {
                            return None;
                        }
                        self.ws();
                        if self.byte() != Some(b':') {
                            return None;
                        }
                        self.ascii(1);
                        n.props.push((key, at, self.value(depth + 1)?));
                        self.ws();
                        if self.byte() != Some(b',') {
                            break;
                        }
                        self.ascii(1);
                    }
                }
                if self.byte() != Some(b'}') {
                    return None;
                }
                self.ascii(1);
            }
            b'[' => {
                if depth > self.depth {
                    return None;
                }
                n.kind = "array";
                self.ascii(1);
                self.ws();
                if self.byte() != Some(b']') {
                    loop {
                        n.items.push(self.value(depth + 1)?);
                        self.ws();
                        if self.byte() != Some(b',') {
                            break;
                        }
                        self.ascii(1);
                    }
                }
                if self.byte() != Some(b']') {
                    return None;
                }
                self.ascii(1);
            }
            b'"' => {
                self.string(false)?;
            }
            b't' | b'f' | b'n' => {
                let token = match self.byte()? {
                    b't' => "true",
                    b'f' => "false",
                    _ => "null",
                };
                if !self.s[self.at..].starts_with(token) {
                    return None;
                }
                self.ascii(token.len());
            }
            _ => {
                if self.byte() == Some(b'-') {
                    self.ascii(1);
                }
                match self.byte()? {
                    b'0' => self.ascii(1),
                    b'1'..=b'9' => {
                        self.ascii(1);
                        while matches!(self.byte(), Some(b'0'..=b'9')) {
                            self.ascii(1);
                        }
                    }
                    _ => return None,
                }
                if self.byte() == Some(b'.') {
                    self.ascii(1);
                    if !matches!(self.byte(), Some(b'0'..=b'9')) {
                        return None;
                    }
                    while matches!(self.byte(), Some(b'0'..=b'9')) {
                        self.ascii(1);
                    }
                }
                if matches!(self.byte(), Some(b'e' | b'E')) {
                    self.ascii(1);
                    if matches!(self.byte(), Some(b'+' | b'-')) {
                        self.ascii(1);
                    }
                    if !matches!(self.byte(), Some(b'0'..=b'9')) {
                        return None;
                    }
                    while matches!(self.byte(), Some(b'0'..=b'9')) {
                        self.ascii(1);
                    }
                }
            }
        }
        n.end = self.utf16;
        Some(n)
    }
}
fn project<'js>(ctx: Ctx<'js>, source: &JsString<'js>, n: Node) -> rquickjs::Result<Object<'js>> {
    let out = Object::new(ctx.clone())?;
    out.set("source", source.clone())?;
    out.set("start", n.start)?;
    out.set("end", n.end)?;
    out.set("kind", n.kind)?;
    if n.kind == "object" {
        let props = Array::new(ctx.clone())?;
        for (i, (key, start, child)) in n.props.into_iter().enumerate() {
            let p = Object::new(ctx.clone())?;
            p.set("key", key)?;
            p.set("start", start)?;
            p.set("value", project(ctx.clone(), source, child)?)?;
            props.set(i, p)?;
        }
        out.set("properties", props)?;
    }
    if n.kind == "array" {
        let items = Array::new(ctx.clone())?;
        for (i, child) in n.items.into_iter().enumerate() {
            items.set(i, project(ctx.clone(), source, child)?)?;
        }
        out.set("items", items)?;
    }
    Ok(out)
}
pub fn scan<'js>(
    ctx: Ctx<'js>,
    source: JsString<'js>,
    depth: f64,
    max_bytes: f64,
) -> rquickjs::Result<Option<Object<'js>>> {
    if !depth.is_finite()
        || depth.fract() != 0.0
        || !(1.0..=64.0).contains(&depth)
        || !max_bytes.is_finite()
        || max_bytes.fract() != 0.0
        || !(0.0..=9007199254740991.0).contains(&max_bytes)
    {
        return Ok(None);
    }
    let raw = source.clone().to_cstring()?;
    if raw.len() > MAX_BYTES || raw.len() as f64 > max_bytes {
        return Ok(None);
    }
    // QuickJS exports WTF-8 for lone surrogates. Let the portable path classify them.
    let bytes = unsafe { std::slice::from_raw_parts(raw.as_ptr().cast::<u8>(), raw.len()) };
    let Ok(s) = std::str::from_utf8(bytes) else {
        return Ok(None);
    };
    let mut scan = Scan {
        s,
        at: 0,
        utf16: 0,
        depth: depth as usize,
        nodes: 0,
    };
    let Some(node) = scan.value(1) else {
        return Ok(None);
    };
    scan.ws();
    if scan.at != s.len() {
        return Ok(None);
    }
    project(ctx, &source, node).map(Some)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn spans_preserve_utf16_unknown_numbers_and_reject_malformed_tokens() {
        let rt = rquickjs::Runtime::new().unwrap();
        let context = rquickjs::Context::full(&rt).unwrap();
        context.with(|ctx|{
            ctx.globals().set("scan",rquickjs::Function::new(ctx.clone(),scan).unwrap()).unwrap();
            let ok:bool=ctx.eval(r#"(()=>{
              const raw=' {"😀":"中","wide":9007199254740993,"future":[1e9999,-0,true,null]} ';
              const n=scan(raw,32,10000),wide=n.properties[1].value;
              if(n.start!==1||n.end!==raw.length-1||n.properties[1].start!==11||raw.slice(wide.start,wide.end)!=='9007199254740993')throw Error('span');
              for(const bad of ['{"x":1,"\\u0078":2}','"\\ud800"','"\\udfff"','"\\u+123"','"\\ud800\\u+123"','[1,]','{"x":01}','1e','1.','+1','nul','"\ud800"'])if(scan(bad,32,10000)!=null)throw Error('accepted '+bad);
              if(scan('"\\ud83d\\ude00"',32,100)==null||scan('[[0]]',1,100)!=null||scan('"中"',32,4)!=null||scan('0',NaN,100)!=null||scan('"'+'x'.repeat(262144)+'"',32,1e6)!=null)throw Error('bounds');
              return true;
            })()"#).unwrap();assert!(ok);
        });
    }
}
