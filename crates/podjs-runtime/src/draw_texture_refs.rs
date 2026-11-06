//! Draw-list texture dependencies. Unknown/auxiliary-surface formats retain the
//! conservative whole-registry invalidation path instead of omitting a resource.
use pocketjs_core::spec::draw_op as op;

pub(crate) fn referenced(words: &[u32]) -> Option<Vec<i32>> {
    // Preserve the legacy resource-only snapshot contract before a scene draws.
    if words.is_empty() { return None; }
    let mut handles=Vec::new();
    let mut at=0usize;
    while at<words.len() {
        let code=words[at];
        let length=match code {
            op::RECT=>4, op::GRAD_RECT=>6, op::SCISSOR=>3, op::SCISSOR_POP=>1,
            op::TRI=>7, op::ROUNDED_CLIP=>5,
            op::TEX_QUAD=>9, op::TEX_QUAD_F32=>11, op::TEX_TRI=>12,
            op::GLYPH_RUN|op::GLYPH_RUN_XFORM|op::GLYPH_RUN_F32=>{
                let count=(*words.get(at+1)?>>16) as usize;
                let (header,stride)=match code {op::GLYPH_RUN=>(3usize,2usize),op::GLYPH_RUN_XFORM=>(5,2),_=>(7,3)};
                header.checked_add(count.checked_mul(stride)?)?
            }
            op::TEXT_RUN=>8usize.checked_add((*words.get(at+7)? as usize).div_ceil(4))?,
            // Auxiliary surfaces can depend on resources not named by the outer
            // list. They and future opcodes use the original complete registry.
            _=>return None,
        };
        let end=at.checked_add(length)?;words.get(at..end)?;
        if matches!(code,op::TEX_QUAD|op::TEX_QUAD_F32|op::TEX_TRI) { handles.push(words[at+1] as i32); }
        at=end;
    }
    handles.sort_unstable();handles.dedup();Some(handles)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn variable_glyph_and_utf8_payloads_do_not_become_texture_commands() {
        let commands=vec![vec![op::RECT,0,0,0],vec![op::GRAD_RECT,0,0,0,0,0],
            vec![op::GLYPH_RUN,1<<16,0,op::TEX_QUAD,19],
            vec![op::GLYPH_RUN_XFORM,1<<16,0,0,0,op::TEX_TRI,20],
            vec![op::GLYPH_RUN_F32,1<<16,0,0,0,0,0,op::TEX_QUAD_F32,21,0],
            vec![op::TEX_QUAD,31,0,0,0,0,0,0,0],
            vec![op::TEX_QUAD_F32,29,0,0,0,0,0,0,0,0,0],
            vec![op::TEX_TRI,31,0,0,0,0,0,0,0,0,0,0],
            vec![op::SCISSOR,0,0],vec![op::SCISSOR_POP],vec![op::TRI,0,0,0,0,0,0],
            vec![op::TEXT_RUN,0,0,0,0,0,0,4,op::TEX_QUAD],vec![op::ROUNDED_CLIP,0,0,0,0]];
        assert_eq!(referenced(&commands.concat()),Some(vec![29,31]));
        for command in commands {
            for n in 1..command.len() {assert_eq!(referenced(&command[..n]),None,"truncated opcode {} at {n}",command[0]);}
        }
    }
    #[test]
    fn empty_auxiliary_unknown_and_oversized_records_keep_conservative_invalidation() {
        for words in [vec![],vec![op::SURFACE_QUAD,0,0,0,0,0,0,0,0],vec![u32::MAX],
            vec![op::TEXT_RUN,0,0,0,0,0,0,u32::MAX],vec![op::GLYPH_RUN_F32,u32::MAX]] {
            assert_eq!(referenced(&words),None);
        }
    }
}
