#!/usr/bin/env bash
# Compare the same source in full-source/deflated vs stripped-source/stored form.
# All files stay in the caller's log directory. No device access or downloads.
set -euo pipefail
if [[ $# != 3 ]]; then
  printf 'usage: %s <baseline-dist> <optimized-dist> <log-dir>\n' "$0" >&2
  exit 2
fi
probe_root="$(cd "$(dirname "$0")/.." && pwd)"
probe_before="$(realpath "$1")"
probe_after="$(realpath "$2")"
mkdir -p "$3"
probe_logdir="$(realpath "$3")"
quickjs_dir="${QUICKJS_DIR:-$(python3 - <<'PY'
import os,pathlib
root=pathlib.Path(os.environ.get('CARGO_HOME',str(pathlib.Path.home()/'.cargo')))/'registry/src'
paths=sorted(root.glob('*/rquickjs-sys-0.12.2/quickjs/quickjs.c'))
if not paths: raise SystemExit('rquickjs-sys 0.12.2 not cached: run cargo build first or set QUICKJS_DIR')
print(paths[0].parent)
PY
)}"
python3 - "$probe_before" "$probe_after" "$probe_logdir" <<'PY'
import pathlib,sys,zlib
before,after,out=map(pathlib.Path,sys.argv[1:])
if (before/'main.js').read_bytes() != (after/'main.js').read_bytes():
    raise SystemExit('comparison requires identical main.js')
for label,root in [('before',before),('after',after)]:
    z=zlib.compressobj(level=6,wbits=-15)
    data=(root/'main.qbc').read_bytes()
    (out/(label+'.deflate')).write_bytes(z.compress(data)+z.flush())
PY
probe_sources=("$probe_root/scripts/profile-bytecode.c" "$quickjs_dir/libregexp.c" "$quickjs_dir/libunicode.c" "$quickjs_dir/dtoa.c")
"${PROBE_CC:-cc}" -O3 -DNDEBUG -D_GNU_SOURCE -I "$quickjs_dir" "${probe_sources[@]}" -lm -lpthread -lz -o "$probe_logdir/probe-native"
probe_run() {
  local probe_label="$1"; shift
  "$@" "$probe_before/main.qbc" 11 "$probe_logdir/before.deflate" > "$probe_logdir/$probe_label-before-deflate.log"
  "$@" "$probe_before/main.qbc" 11 > "$probe_logdir/$probe_label-before-stored.log"
  "$@" "$probe_after/main.qbc" 11 "$probe_logdir/after.deflate" > "$probe_logdir/$probe_label-after-deflate.log"
  "$@" "$probe_after/main.qbc" 11 > "$probe_logdir/$probe_label-after-stored.log"
}
probe_run native "$probe_logdir/probe-native"
if [[ -n "${PROBE_ARM_SYSROOT:-}" ]]; then
  probe_sysroot="$(realpath "$PROBE_ARM_SYSROOT")"
  : "${PROBE_ARM_GCCLIB:?set the path to an ARMv7 hard-float libgcc.a}"
  clang --target=armv7-linux-musleabihf --sysroot="$probe_sysroot" -march=armv7-a -mfloat-abi=hard -mfpu=vfpv3-d16 \
    -O3 -DNDEBUG -D_GNU_SOURCE -I "$quickjs_dir" "${probe_sources[@]}" -fuse-ld=lld -nostdlib \
    "$probe_sysroot/usr/lib/crt1.o" "$probe_sysroot/usr/lib/crti.o" \
    -L"$probe_sysroot/usr/lib" -L"$probe_sysroot/lib" -lc -lz -lm "$PROBE_ARM_GCCLIB" "$probe_sysroot/usr/lib/crtn.o" \
    -Wl,--dynamic-linker,/lib/ld-musl-armhf.so.1 -o "$probe_logdir/probe-arm"
  probe_run arm qemu-arm -cpu cortex-a7 -L "$probe_sysroot" "$probe_sysroot/lib/ld-musl-armhf.so.1" \
    --library-path "$probe_sysroot/usr/lib" "$probe_logdir/probe-arm"
fi
python3 - "$probe_logdir" <<'PY'
import pathlib,re,statistics,json,sys
out=pathlib.Path(sys.argv[1]);summary={}
for path in sorted(out.glob('*-*.log')):
    rows=[dict((k,float(v)) for k,v in re.findall(r'(\w+)=([0-9.]+)',line))
          for line in path.read_text().splitlines() if line.startswith('K7_READ')]
    if not rows: continue
    for row in rows: row['pre_execute_ms']=row['asset_ms']+row['read_object_ms']+row['instantiate_ms']
    summary[path.stem]={k:statistics.median(row[k] for row in rows) for k in rows[0]}
    print(path.stem, json.dumps(summary[path.stem],sort_keys=True))
(out/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
PY
