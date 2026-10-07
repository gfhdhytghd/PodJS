//! Bounded atlas wrapping with the portable layout's exact cluster/space rules.
use pocket_ui_surface::UiSurface;
use rquickjs::{Ctx, Function, Object, String as JsString};

fn whitespace(c: char) -> bool {
    matches!(c,'\u{9}'..='\u{d}'|'\u{20}'|'\u{a0}'|'\u{1680}'|'\u{2000}'..='\u{200a}'|'\u{2028}'|'\u{2029}'|'\u{202f}'|'\u{205f}'|'\u{3000}'|'\u{feff}')
}
fn mark(c: char) -> bool {
    matches!(c,'\u{200d}'|'\u{fe0f}'|'\u{300}'..='\u{36f}'|'\u{1ab0}'..='\u{1aff}'|'\u{1dc0}'..='\u{1dff}'|'\u{20d0}'..='\u{20ff}')
}
pub fn wrap(
    text: &str,
    width: f64,
    scale: f64,
    tracking: f64,
    measure: impl Fn(&str) -> f64,
) -> Option<Vec<String>> {
    if text.encode_utf16().count() > 4096
        || !width.is_finite()
        || width <= 0.0
        || !scale.is_finite()
        || scale <= 0.0
        || !tracking.is_finite()
    {
        return None;
    }
    let normalized = text.replace("\r\n", "\n").replace('\r', "\n");
    let mut lines = Vec::new();
    for paragraph in normalized.split('\n') {
        if paragraph.is_empty() {
            lines.push(String::new());
            continue;
        }
        let mut ranges: Vec<(usize, usize)> = Vec::new();
        let mut previous = '\0';
        for (start, c) in paragraph.char_indices() {
            let end = start + c.len_utf8();
            if !ranges.is_empty() && (mark(c) || previous == '\u{200d}') {
                ranges.last_mut().unwrap().1 = end;
            } else {
                ranges.push((start, end));
            }
            previous = c;
        }
        let mut line = String::new();
        let mut pending = String::new();
        for (start, end) in ranges {
            let g = &paragraph[start..end];
            let has_space = g.chars().any(whitespace);
            // In ordinary clusters U+3000 is the sole intersection of ECMAScript
            // whitespace and the CJK scripts/range. Exotic ZWJ space clusters retain
            // the portable Unicode-property check instead of guessing script tables.
            if has_space && g.contains('\u{200d}') {
                return None;
            }
            let space = has_space && !g.contains('\u{3000}');
            let candidate = format!("{line}{pending}{g}");
            let spacing = if tracking == 0.0 {
                0.0
            } else {
                candidate.chars().count().saturating_sub(1) as f64 * tracking
            };
            if measure(&candidate) * scale + spacing > width && !line.is_empty() {
                lines.push(std::mem::take(&mut line));
                line.push_str(&pending);
                line.push_str(g);
                pending.clear();
                continue;
            }
            if space {
                pending.push_str(g);
            } else {
                line.push_str(&pending);
                line.push_str(g);
                pending.clear();
            }
        }
        line.push_str(&pending);
        lines.push(line);
    }
    Some(lines)
}
pub fn mount<'js>(ctx: Ctx<'js>, pod: &Object<'js>, surface: UiSurface) -> rquickjs::Result<()> {
    pod.set(
        "wrapAtlasTextChunk",
        Function::new(
            ctx,
            move |text: JsString, slot: i32, width: f64, scale: f64, tracking: f64| {
                if !(0..=255).contains(&slot) {
                    return None;
                }
                let text = text.to_string().ok()?;
                surface.ensure_font(slot as u8);
                surface.with_ui(|ui| {
                    wrap(&text, width, scale, tracking, |s| {
                        ui.measure_text(s, slot as u8) as f64
                    })
                })
            },
        )?,
    )?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn boundaries_spaces_clusters_and_rejection() {
        let measure = |s: &str| s.encode_utf16().count() as f64;
        assert_eq!(
            wrap("a b  c\r\n中　文🙂", 3.0, 1.0, 0.0, measure).unwrap(),
            vec!["a b", "  c", "中　文", "🙂"]
        );
        assert_eq!(
            wrap("a\u{200d}b", 1.0, 1.0, 0.0, measure).unwrap(),
            vec!["a\u{200d}b"]
        );
        assert!(wrap("中\u{200d} ", 3.0, 1.0, 0.0, measure).is_none());
        assert!(wrap(&"a".repeat(4097), 3.0, 1.0, 0.0, measure).is_none());
    }
}
