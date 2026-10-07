//! Bounded XML tree jobs; ASCII XML names and permissive token syntax. Entity
//! decoding and aggregate text follow the SDK compatibility contract. No feed
//! formats, selectors, URLs or application identities live here.
use regex::Regex;
use rquickjs::{Array, Ctx, Function, Object, String as JsString};
use std::{cell::RefCell, collections::HashMap, rc::Rc, sync::OnceLock, time::Instant};
const LIMIT: usize = 8 * 1024 * 1024;
struct Patterns {
    token: Regex,
    attr: Regex,
    self_close: Regex,
}
fn patterns() -> &'static Patterns {
    static P: OnceLock<Patterns> = OnceLock::new();
    P.get_or_init(||{let ws=r"\t\n\x0b\x0c\r \u{a0}\u{1680}\u{2000}-\u{200a}\u{2028}\u{2029}\u{202f}\u{205f}\u{3000}\u{feff}";Patterns{
 token:Regex::new(&format!(r"(?s)<!--.*?-->|<!\[CDATA\[(.*?)\]\]>|<\?[^>]*\?>|<[{ws}]*(/?)[{ws}]*([A-Za-z0-9_:.-]+)([^>]*)>|([^<]+)")).unwrap(),
 attr:Regex::new(&format!(r#"(?s)([A-Za-z0-9_:.-]+)[{ws}]*=[{ws}]*(?:"(.*?)"|'(.*?)')"#)).unwrap(),
 self_close:Regex::new(&format!(r"/[{ws}]*\z")).unwrap()}})
}
struct Node {
    parent: usize,
    name: String,
    attrs: Vec<String>,
    text: String,
}
struct Job {
    source: String,
    ready: bool,
    cursor: usize,
    stack: Vec<usize>,
    nodes: Vec<Node>,
    error: Option<String>,
    failed: bool,
    scanned: bool,
    output: usize,
    text_offset: usize,
}
struct Row {
    id: usize,
    parent: usize,
    name: String,
    attrs: Vec<String>,
    text: String,
    last: bool,
}
impl Job {
    fn new() -> Self {
        Self {
            source: String::new(),
            ready: false,
            cursor: 0,
            stack: vec![0],
            nodes: vec![Node {
                parent: 0,
                name: "#root".into(),
                attrs: vec![],
                text: String::new(),
            }],
            error: None,
            failed: false,
            scanned: false,
            output: 0,
            text_offset: 0,
        }
    }
    fn scan(&mut self) {
        let began = Instant::now();
        let start = self.cursor;
        let mut count = 0;
        while self.cursor < self.source.len() {
            let Some(c) = patterns().token.captures_at(&self.source, self.cursor) else {
                self.cursor = self.source.len();
                break;
            };
            self.cursor = c.get(0).unwrap().end();
            count += 1;
            let top = *self.stack.last().unwrap_or(&0);
            if let Some(text) = c.get(1).or_else(|| c.get(5)) {
                self.nodes[top].text.push_str(text.as_str());
            } else if let Some(name) = c.get(3) {
                let name = name.as_str().to_ascii_lowercase();
                if c.get(2).is_some_and(|s| s.as_str() == "/") {
                    let id = self.stack.pop().unwrap_or(0);
                    if id == 0 || self.nodes[id].name != name {
                        self.error = Some(format!(
                            "Malformed XML: unexpected closing tag {}",
                            c.get(3).unwrap().as_str()
                        ));
                        break;
                    }
                    let parent = *self.stack.last().unwrap_or(&0);
                    let text = self.nodes[id].text.clone();
                    self.nodes[parent].text.push_str(&text);
                } else {
                    if self.nodes.len() >= 65536 {
                        self.failed = true;
                        break;
                    }
                    let raw = c.get(4).map_or("", |m| m.as_str());
                    let mut attrs: Vec<String> = vec![];
                    for attr in patterns().attr.captures_iter(raw) {
                        let key = attr[1].to_ascii_lowercase();
                        let Some(value) = super::html::decode(
                            attr.get(2).or_else(|| attr.get(3)).unwrap().as_str(),
                        ) else {
                            self.failed = true;
                            break;
                        };
                        if let Some(i) = attrs.iter().step_by(2).position(|k| k == &key) {
                            attrs[i * 2 + 1] = value;
                        } else {
                            attrs.push(key);
                            attrs.push(value);
                        }
                    }
                    if self.failed {
                        break;
                    }
                    let id = self.nodes.len();
                    self.nodes.push(Node {
                        parent: top,
                        name,
                        attrs,
                        text: String::new(),
                    });
                    if !patterns().self_close.is_match(raw) {
                        self.stack.push(id);
                    }
                }
            }
            if count >= 64 || self.cursor - start >= 4096 || began.elapsed().as_micros() >= 1000 {
                return;
            }
        }
        if self.error.is_none()
            && !self.failed
            && (self.stack.len() != 1
                || self
                    .nodes
                    .iter()
                    .filter(|n| n.parent == 0 && n.name != "#root")
                    .count()
                    != 1)
        {
            self.error = Some("Malformed XML: expected one root element".into());
        }
        self.scanned = true;
    }
    fn rows(&mut self) -> Vec<Row> {
        let mut rows = vec![];
        let mut bytes = 0;
        while self.output < self.nodes.len() && rows.len() < 16 && bytes < 4096 {
            let n = &self.nodes[self.output];
            let start = self.text_offset;
            let mut end = (start + 4096).min(n.text.len());
            while !n.text.is_char_boundary(end) {
                end -= 1;
            }
            let text = n.text[start..end].to_owned();
            bytes += text.len();
            let first = start == 0;
            let last = end == n.text.len();
            rows.push(Row {
                id: self.output,
                parent: n.parent,
                name: if first { n.name.clone() } else { String::new() },
                attrs: if first { n.attrs.clone() } else { vec![] },
                text,
                last,
            });
            if last {
                self.output += 1;
                self.text_offset = 0;
            } else {
                self.text_offset = end;
            }
        }
        rows
    }
}
#[derive(Default)]
struct Jobs {
    next: u32,
    map: HashMap<u32, Job>,
}
pub fn mount<'js>(ctx: Ctx<'js>, pod: &Object<'js>) -> rquickjs::Result<()> {
    let state = Rc::new(RefCell::new(Jobs::default()));
    let begin = state.clone();
    pod.set(
        "xmlBegin",
        Function::new(ctx.clone(), move || {
            let mut s = begin.borrow_mut();
            if s.map.len() >= 4 {
                return None;
            }
            s.next = s.next.checked_add(1)?;
            let id = s.next;
            s.map.insert(id, Job::new());
            Some(id)
        })?,
    )?;
    let append = state.clone();
    pod.set(
        "xmlAppend",
        Function::new(
            ctx.clone(),
            move |id: u32, part: JsString, last: bool| -> bool {
                let Ok(part) = part.to_string() else {
                    append.borrow_mut().map.remove(&id);
                    return false;
                };
                let mut s = append.borrow_mut();
                let Some(j) = s.map.get_mut(&id) else {
                    return false;
                };
                if j.ready || part.len() > 16384 || j.source.len() + part.len() > LIMIT {
                    s.map.remove(&id);
                    return false;
                }
                j.source.push_str(&part);
                j.ready = last;
                true
            },
        )?,
    )?;
    let next = state.clone();
    pod.set(
        "xmlNext",
        Function::new(
            ctx.clone(),
            move |ctx: Ctx<'js>, id: u32| -> rquickjs::Result<Option<Array<'js>>> {
                let mut s = next.borrow_mut();
                let Some(j) = s.map.get_mut(&id) else {
                    return Ok(None);
                };
                if !j.ready {
                    return Ok(None);
                }
                if !j.scanned {
                    j.scan();
                }
                if j.failed {
                    s.map.remove(&id);
                    return Ok(None);
                }
                let result = Array::new(ctx.clone())?;
                if let Some(error) = &j.error {
                    result.set(0, 3)?;
                    result.set(2, error.clone())?;
                    s.map.remove(&id);
                    return Ok(Some(result));
                }
                let rows = if j.scanned { j.rows() } else { vec![] };
                let done = j.scanned && j.output == j.nodes.len();
                result.set(
                    0,
                    if done {
                        2
                    } else if j.scanned {
                        1
                    } else {
                        0
                    },
                )?;
                let output = Array::new(ctx.clone())?;
                for (i, r) in rows.into_iter().enumerate() {
                    let a = Array::new(ctx.clone())?;
                    a.set(0, r.id as u32)?;
                    a.set(1, r.parent as u32)?;
                    a.set(2, r.name)?;
                    let attrs = Array::new(ctx.clone())?;
                    for (i, v) in r.attrs.into_iter().enumerate() {
                        attrs.set(i, v)?;
                    }
                    a.set(3, attrs)?;
                    a.set(4, r.text)?;
                    a.set(5, r.last)?;
                    output.set(i, a)?;
                }
                result.set(1, output)?;
                if done {
                    s.map.remove(&id);
                }
                Ok(Some(result))
            },
        )?,
    )?;
    pod.set(
        "xmlCancel",
        Function::new(ctx, move |id: u32| {
            state.borrow_mut().map.remove(&id);
        })?,
    )?;
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn tree_errors_and_chunks() {
        let mut j = Job::new();
        j.source =
            "<?x?><rss><channel><title a='&amp;'>中<b>🙂</b>tail</title><x/></channel></rss>"
                .into();
        j.ready = true;
        while !j.scanned {
            j.scan();
        }
        assert!(j.error.is_none());
        assert_eq!(j.nodes[3].text, "中🙂tail");
        assert_eq!(j.nodes[3].attrs, vec!["a", "&"]);
        let mut bad = Job::new();
        bad.source = "<rss></channel>".into();
        bad.ready = true;
        bad.scan();
        assert_eq!(
            bad.error.as_deref(),
            Some("Malformed XML: unexpected closing tag channel")
        );
    }
    #[test]
    fn large_text_rows_bound() {
        let mut j = Job::new();
        j.source = format!("<root><![CDATA[{}]]></root>", "中🙂".repeat(10000));
        j.ready = true;
        while !j.scanned {
            j.scan();
        }
        assert!(j.error.is_none());
        let mut count = 0;
        while j.output < j.nodes.len() {
            for r in j.rows() {
                assert!(r.text.len() <= 4096);
                count += 1;
            }
        }
        assert!(count > 30);
    }
}
