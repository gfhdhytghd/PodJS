//! Generic lightweight HTML text prefix/first-image extraction. Deliberately
//! follows the SDK regex compatibility dialect, not HTML5 DOM semantics.
use regex::Regex;
use rquickjs::{Array, Ctx, Function, Object, String as JsString};
use std::sync::OnceLock;
const WS: &str = r"\t\n\x0b\x0c\r \u{a0}\u{1680}\u{2000}-\u{200a}\u{2028}\u{2029}\u{202f}\u{205f}\u{3000}\u{feff}";
struct Patterns {
    comments: Regex,
    hidden: Regex,
    close: Vec<Regex>,
    blocks: Regex,
    block_close: Regex,
    tags: Regex,
    whitespace: Regex,
    image: Regex,
    image_hidden: Regex,
    image_close: Vec<Regex>,
}
fn patterns() -> &'static Patterns {
    static P: OnceLock<Patterns> = OnceLock::new();
    P.get_or_init(|| Patterns {
        comments: Regex::new(r"(?s)<!--.*?-->").unwrap(),
        hidden: Regex::new(&format!(r"<[{WS}]*([sS][cC][rR][iI][pP][tT]|[sS][tT][yY][lL][eE]|[nN][oO][sS][cC][rR][iI][pP][tT])[^>]*>")).unwrap(),
        close: ["script", "style", "noscript"]
            .iter()
            .map(|tag| Regex::new(&format!(r"<[{WS}]*/[{WS}]*{}[{WS}]*>",tag.chars().map(|c|format!("[{c}{}]",c.to_ascii_uppercase())).collect::<String>())).unwrap())
            .collect(),
        blocks: Regex::new(&format!(
            r"<[{WS}]*([bB][rR]|[pP]|[dD][iI][vV]|[lL][iI]|[tT][rR]|[hH][1-6]|[bB][lL][oO][cC][kK][qQ][uU][oO][tT][eE]|[pP][rR][eE]|[sS][eE][cC][tT][iI][oO][nN]|[aA][rR][tT][iI][cC][lL][eE])[^>]*>"
        ))
        .unwrap(),
        block_close: Regex::new(&format!(
            r"<[{WS}]*/[{WS}]*([pP]|[dD][iI][vV]|[lL][iI]|[tT][rR]|[hH][1-6]|[bB][lL][oO][cC][kK][qQ][uU][oO][tT][eE]|[pP][rR][eE]|[sS][eE][cC][tT][iI][oO][nN]|[aA][rR][tT][iI][cC][lL][eE])[{WS}]*>"
        ))
        .unwrap(),
        tags: Regex::new(r"<[^>]*>").unwrap(),
        whitespace: Regex::new(&format!(r"[{WS}]+")).unwrap(),
        image_hidden: Regex::new(r"(?s)<!--.*?-->|<([sS][cC][rR][iI][pP][tT]|[sS][tT][yY][lL][eE])(?-u:\b)[^>]*>").unwrap(),
        image_close: ["script", "style"].iter().map(|tag| Regex::new(&format!(r"</{}[{WS}]*>",tag.chars().map(|c|format!("[{c}{}]",c.to_ascii_uppercase())).collect::<String>())).unwrap()).collect(),
        image: Regex::new(&format!(
            r#"<[iI][mM][gG](?-u:\b)[^>]*?[{WS}]+[sS][rR][cC][{WS}]*=[{WS}]*(?:"([^"]*)"|'([^']*)'|([^{WS}>]+))"#
        ))
        .unwrap(),
    })
}
fn hidden(source: &str, include_noscript: bool) -> String {
    let p = patterns();
    let mut out = String::new();
    let mut cursor = 0;
    let mut search = 0;
    while let Some(c) = p.hidden.captures_at(source, search) {
        let tag = c[1].to_ascii_lowercase();
        let index = match tag.as_str() {
            "script" => 0,
            "style" => 1,
            _ => 2,
        };
        let open = c.get(0).unwrap();
        search = open.end();
        if index == 2 && !include_noscript {
            continue;
        }
        if let Some(close) = p.close[index].find_at(source, open.end()) {
            out.push_str(&source[cursor..open.start()]);
            cursor = close.end();
            search = cursor;
        }
    }
    out.push_str(&source[cursor..]);
    out
}
fn image_safe(source:&str)->String {
 let p=patterns();let mut out=String::new();let mut cursor=0;let mut search=0;
 while let Some(c)=p.image_hidden.captures_at(source,search){let open=c.get(0).unwrap();search=open.end();
  let end=if let Some(tag)=c.get(1){let index=if tag.as_str().eq_ignore_ascii_case("script"){0}else{1};p.image_close[index].find_at(source,open.end()).map(|m|m.end())}else{Some(open.end())};
  if let Some(end)=end{out.push_str(&source[cursor..open.start()]);cursor=end;search=end;}
 }out.push_str(&source[cursor..]);out
}
fn preview(source: &str, limit: usize) -> Option<(usize, String, String)> {
    let p = patterns();
    let clean = p.comments.replace_all(source, "");
    let content = hidden(&clean, true);
    let content = p.blocks.replace_all(&content, "\n");
    let content = p.block_close.replace_all(&content, "\n");
    let stripped = p.tags.replace_all(&content, "");
    let decoded = super::html::decode(&stripped)?;
    let collapsed = p.whitespace.replace_all(&decoded, " ");
    let plain = collapsed.trim_matches(' ');
    let units = plain.encode_utf16().count();
    let mut prefix = String::new();
    let mut count = 0;
    for ch in plain.chars() {
        if count + ch.len_utf16() > limit {
            break;
        }
        prefix.push(ch);
        count += ch.len_utf16();
    }
    let safe = image_safe(source);
    let src = if let Some(c) = p.image.captures(&safe) {
        super::html::decode(
            c.get(1)
                .or_else(|| c.get(2))
                .or_else(|| c.get(3))
                .unwrap()
                .as_str(),
        )?
    } else {
        String::new()
    };
    Some((units, prefix, src))
}
pub fn mount<'js>(ctx: Ctx<'js>, pod: &Object<'js>) -> rquickjs::Result<()> {
    pod.set(
        "htmlPreview",
        Function::new(
            ctx,
            move |ctx: Ctx<'js>,
                  source: JsString,
                  limit: u32|
                  -> rquickjs::Result<Option<Array<'js>>> {
                let Ok(source) = source.to_string() else {
                    return Ok(None);
                };
                if source.len() > 8 * 1024 * 1024 || limit > 4096 {
                    return Ok(None);
                }
                let Some((length, prefix, src)) = preview(&source, limit as usize) else {
                    return Ok(None);
                };
                let out = Array::new(ctx)?;
                out.set(0, length as u32)?;
                out.set(1, prefix)?;
                out.set(2, src)?;
                Ok(Some(out))
            },
        )?,
    )?;
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn entities_hidden_unicode() {
        assert_eq!(
            preview("<p>中&amp;🙂</p><script>x</script><img src='&amp;a'>", 4),
            Some((4, "中&🙂".into(), "&a".into()))
        );
        assert!(preview("&#xd800;", 60).is_none());
        assert_eq!(
            preview("<pancake>x</pancake><img中 src=x/>", 60),
            Some((1, "x".into(), "x/".into()))
        );
    }
}
