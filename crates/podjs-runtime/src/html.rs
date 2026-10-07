//! Incremental, bounded HTML fragment tree. No application selectors or blocks.
//! Flat preorder rows are transferred directly to QuickJS (no JSON round trip).
use regex::Regex;
use rquickjs::{Array, Ctx, Function, Object};
use std::{cell::RefCell, collections::HashMap, rc::Rc, sync::OnceLock, time::Instant};
const SOURCE_LIMIT: usize = 4 * 1024 * 1024;
const TOKEN_LIMIT: usize = 16384;
const NODE_LIMIT: usize = 65536;
const WS: &str = r"\t\n\x0b\x0c\r \u{a0}\u{1680}\u{2000}-\u{200a}\u{2028}\u{2029}\u{202f}\u{205f}\u{3000}\u{feff}";
struct Patterns {
    token: Regex,
    tag: Regex,
    attr: Regex,
    entity: Regex,
    tail: Regex,
    whitespace: Regex,
}
fn patterns() -> &'static Patterns {
    static P: OnceLock<Patterns> = OnceLock::new();
    P.get_or_init(|| Patterns {
        token: Regex::new(&format!(r#"(?s)\A(?:<!--.*?(?:-->|\z)|<![^>]*>|</?[a-zA-Z][a-zA-Z0-9_:-]*(?:[{WS}]+(?:[^'">]|"[^"]*"|'[^']*')*)?[{WS}]*/?>|[^<]+|<)"#)).unwrap(),
        tag: Regex::new(r"\A<(/?)([a-zA-Z0-9_:-]+)").unwrap(),
        attr: Regex::new(&format!(r#"([^{WS}=/>]+)(?:[{WS}]*=[{WS}]*(?:"([^"]*)"|'([^']*)'|([^{WS}>]+)))?"#)).unwrap(),
        entity: Regex::new(r"(?i)&(\#x?[0-9a-f]+|[a-z][a-z0-9_]+);").unwrap(),
        whitespace: Regex::new(&format!(r"[{WS}]+")).unwrap(),
        tail: Regex::new(&format!(r"/?[{WS}]*>\z")).unwrap(),
    })
}
/// Small named-entity vocabulary matches the SDK's fragment dialect. Unknown
/// names remain literal. Reject surrogate codepoints for a lossless JS fallback.
pub(crate) fn decode(input: &str) -> Option<String> {
    let mut out = String::with_capacity(input.len());
    let mut cursor = 0;
    for c in patterns().entity.captures_iter(input) {
        let m = c.get(0).unwrap();
        out.push_str(&input[cursor..m.start()]);
        cursor = m.end();
        let key = c[1].to_ascii_lowercase();
        if let Some(numeric) = key.strip_prefix('#') {
            let (digits, radix) = numeric.strip_prefix('x').map_or((numeric, 10), |s| (s, 16));
            let prefix: String = digits.chars().take_while(|c| c.is_digit(radix)).collect();
            if prefix.is_empty() {
                continue;
            }
            let number = if radix == 10 {
                prefix.parse::<f64>().unwrap_or(f64::INFINITY)
            } else {
                prefix.chars().fold(0.0, |n, digit| {
                    n * 16.0 + digit.to_digit(16).unwrap() as f64
                })
            };
            // JS parseInt may overflow to Infinity, which is removed rather
            // than clamped by the compatibility entity contract.
            if !number.is_finite() {
                continue;
            }
            let n = number.min(0x10ffff as f64) as u32;
            if (0xd800..=0xdfff).contains(&n) {
                return None;
            }
            out.push(char::from_u32(n)?);
        } else {
            out.push_str(match key.as_str() {
                "amp" => "&",
                "lt" => "<",
                "gt" => ">",
                "quot" => "\"",
                "apos" => "'",
                "nbsp" => " ",
                _ => m.as_str(),
            });
        }
    }
    out.push_str(&input[cursor..]);
    Some(out)
}
struct Job {
    source: String,
    ready: bool,
    cursor: usize,
    stack: Vec<(String, Option<usize>)>,
    nodes: usize,
    skip: Vec<String>,
}
struct Row {
    parent: usize,
    tag: String,
    attrs: Vec<(String, String)>,
    text: String,
}
impl Job {
    fn new(skip: String) -> Self {
        Self {
            source: String::new(),
            ready: false,
            cursor: 0,
            stack: vec![("body".into(), Some(0))],
            nodes: 1,
            skip: skip.split(',').map(str::to_owned).collect(),
        }
    }
    fn step(&mut self) -> Option<(bool, Vec<Row>)> {
        if !self.ready {
            return None;
        }
        let mut rows = Vec::new();
        let began = Instant::now();
        let start = self.cursor;
        let mut tokens = 0;
        while self.cursor < self.source.len() {
            // Regex only sees a bounded source window. A token touching the
            // window end is conservatively rejected, never silently split.
            let mut end = (self.cursor + TOKEN_LIMIT).min(self.source.len());
            while !self.source.is_char_boundary(end) {
                end -= 1;
            }
            let window = &self.source[self.cursor..end];
            let m = patterns().token.find(window)?;
            if end < self.source.len() && (m.end() == window.len() || m.as_str() == "<") {
                return None;
            }
            let value = m.as_str();
            self.cursor += m.end();
            tokens += 1;
            if !value.starts_with("<!") {
                if let Some(tag) = patterns().tag.captures(value) {
                    let name = tag[2].to_ascii_lowercase();
                    if &tag[1] == "/" {
                        if let Some(i) = self.stack.iter().rposition(|(t, _)| t == &name) {
                            if i > 0 {
                                self.stack.truncate(i);
                            }
                        }
                    } else {
                        if (name == "li" || name == "p")
                            && self.stack.last()?.0 == name
                            && self.stack.len() > 1
                        {
                            self.stack.pop();
                        }
                        let parent = self.stack.last()?.1;
                        let visible = parent.is_some() && !self.skip.contains(&name);
                        let id = if visible {
                            let tail = patterns().tail.replace(&value[tag.get(0)?.end()..], "");
                            let mut attrs: Vec<(String, String)> = Vec::new();
                            for a in patterns().attr.captures_iter(&tail) {
                                let key = a[1].to_lowercase();
                                let val = decode(
                                    a.get(2)
                                        .or_else(|| a.get(3))
                                        .or_else(|| a.get(4))
                                        .map_or("", |m| m.as_str()),
                                )?;
                                if let Some(old) = attrs.iter_mut().find(|(k, _)| k == &key) {
                                    old.1 = val;
                                } else {
                                    attrs.push((key, val));
                                }
                            }
                            let id = self.nodes;
                            self.nodes += 1;
                            rows.push(Row {
                                parent: parent?,
                                tag: name.clone(),
                                attrs,
                                text: String::new(),
                            });
                            Some(id)
                        } else {
                            None
                        };
                        if !matches!(
                            name.as_str(),
                            "area"
                                | "base"
                                | "br"
                                | "col"
                                | "embed"
                                | "hr"
                                | "img"
                                | "input"
                                | "link"
                                | "meta"
                                | "param"
                                | "source"
                                | "track"
                                | "wbr"
                        ) && !value.ends_with("/>")
                        {
                            self.stack.push((name, id));
                        }
                    }
                } else if let Some(parent) = self.stack.last()?.1 {
                    rows.push(Row {
                        parent,
                        tag: String::new(),
                        attrs: Vec::new(),
                        text: decode(value)?,
                    });
                    self.nodes += 1;
                }
            }
            if self.nodes > NODE_LIMIT || self.stack.len() > NODE_LIMIT {
                return None;
            }
            if tokens >= 64
                || self.cursor - start >= TOKEN_LIMIT
                || began.elapsed().as_micros() >= 1000
            {
                break;
            }
        }
        Some((self.cursor == self.source.len(), rows))
    }
}
#[derive(Default)]
struct Jobs {
    next: u32,
    jobs: HashMap<u32, Job>,
}
pub fn mount<'js>(ctx: Ctx<'js>, pod: &Object<'js>) -> rquickjs::Result<()> {
    pod.set(
        "htmlNormalizeWhitespace",
        Function::new(ctx.clone(), |source: String| -> Option<String> {
            if source.len() > 16384 {
                return None;
            }
            Some(patterns().whitespace.replace_all(&source, " ").into_owned())
        })?,
    )?;
    // Mounted namespace owns all retained state; runtime destruction releases it.
    patterns();
    let jobs = Rc::new(RefCell::new(Jobs::default()));
    let begin = jobs.clone();
    pod.set(
        "htmlBegin",
        Function::new(ctx.clone(), move |skip: String| -> Option<u32> {
            let mut state = begin.borrow_mut();
            if state.jobs.len() >= 4 || skip.len() > 1024 {
                return None;
            }
            state.next = state.next.wrapping_add(1).max(1);
            let id = state.next;
            state.jobs.insert(id, Job::new(skip));
            Some(id)
        })?,
    )?;
    let append = jobs.clone();
    pod.set(
        "htmlAppend",
        Function::new(
            ctx.clone(),
            move |id: u32, part: String, last: bool| -> bool {
                let mut state = append.borrow_mut();
                let Some(job) = state.jobs.get_mut(&id) else {
                    return false;
                };
                if job.ready || part.len() > 16384 || job.source.len() + part.len() > SOURCE_LIMIT {
                    state.jobs.remove(&id);
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
        "htmlNext",
        Function::new(
            ctx.clone(),
            move |ctx: Ctx<'js>, id: u32| -> rquickjs::Result<Option<Array<'js>>> {
                let result = {
                    let mut state = next.borrow_mut();
                    let result = state.jobs.get_mut(&id).and_then(Job::step);
                    if result.as_ref().is_none_or(|(done, _)| *done) {
                        state.jobs.remove(&id);
                    }
                    result
                };
                let Some((done, rows)) = result else {
                    return Ok(None);
                };
                let output = Array::new(ctx.clone())?;
                output.set(0, done)?;
                let nodes = Array::new(ctx.clone())?;
                for (i, row) in rows.into_iter().enumerate() {
                    let node = Array::new(ctx.clone())?;
                    node.set(0, row.parent as u32)?;
                    node.set(1, row.tag)?;
                    let attrs = Array::new(ctx.clone())?;
                    for (j, (key, value)) in row.attrs.into_iter().enumerate() {
                        attrs.set(j * 2, key)?;
                        attrs.set(j * 2 + 1, value)?;
                    }
                    node.set(2, attrs)?;
                    node.set(3, row.text)?;
                    nodes.set(i, node)?;
                }
                output.set(1, nodes)?;
                Ok(Some(output))
            },
        )?,
    )?;
    pod.set(
        "htmlCancel",
        Function::new(ctx, move |id: u32| {
            jobs.borrow_mut().jobs.remove(&id);
        })?,
    )?;
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn tokenizer_tree_and_entities() {
        let mut j = Job::new("script,style".into());
        j.source =
            "<p a='&AMP;' a=x>中&nbsp;🙂<img src=\"a>b\"><p>two<script><p>bad</script>tail".into();
        j.ready = true;
        let mut rows = Vec::new();
        loop {
            let (done, batch) = j.step().unwrap();
            rows.extend(batch);
            if done {
                break;
            }
        }
        assert_eq!(rows.len(), 6);
        assert_eq!(rows[0].attrs, vec![("a".into(), "x".into())]);
        assert_eq!(rows[1].text, "中 🙂");
        assert_eq!(rows.last().unwrap().text, "tail");
    }
    #[test]
    fn real_bridge_is_bounded_and_cancelled() {
        let rt = rquickjs::Runtime::new().unwrap();
        let context = rquickjs::Context::full(&rt).unwrap();
        context.with(|ctx|{let pod=Object::new(ctx.clone()).unwrap();mount(ctx.clone(),&pod).unwrap();ctx.globals().set("pod",pod).unwrap();let ok:bool=ctx.eval(r#"const id=pod.htmlBegin('script,style');pod.htmlAppend(id,'<p>hi&amp;</p>',true);const r=pod.htmlNext(id);if(!r[0]||r[1][1][3]!=='hi&')throw Error('bridge');if(pod.htmlNext(id)!=null)throw Error('finished');const x=pod.htmlBegin('');pod.htmlCancel(x);if(pod.htmlAppend(x,'a',true))throw Error('cancel');const y=pod.htmlBegin('');pod.htmlAppend(y,'a'.repeat(20000),true);if(pod.htmlNext(y)!=null)throw Error('bound');true"#).unwrap();assert!(ok);});
    }
}
