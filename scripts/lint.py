#!/usr/bin/env python3
"""Check owned source without fixes, warning baselines, or error suppression."""
import json
import os
import shutil
import pathlib
import subprocess
import sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
app = (ROOT / 'framework.lock.json').is_file()
rows = []
output=pathlib.Path(os.environ.get('PODJS_LINT_OUTPUT',ROOT/'.cache/test-gate/lint'))
output.mkdir(parents=True,exist_ok=True)
print('lint diagnostics: '+str(output),flush=True)

def run(name, args):
    print(f'lint: {name}', flush=True)
    try:
        code = subprocess.call(args, cwd=ROOT)
    except FileNotFoundError as error:
        print(error, file=sys.stderr)
        code = 127
    rows.append((name, code))
files = [p for p in subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=ROOT).decode().split('\x00') if p]
owned = [p for p in files if not p.startswith(('vendor/', '.codex-tmp/', 'artifacts/', 'dist/')) and '/build/' not in p and ('/node_modules/' not in p)]
js = [p for p in owned if pathlib.Path(p).suffix in ('.ts', '.tsx', '.js', '.mjs', '.cjs')]
for p in ('eslint.config.mjs', 'scripts/lint.py'):
    if pathlib.Path(ROOT / p).is_file() and p.endswith('.mjs') and (p not in js):
        js.append(p)
if js:
    run('eslint', ['bun', 'x', '--no-install', 'eslint', '--cache', '--cache-strategy', 'content', '--cache-location', '.cache/test-gate/eslint', '--format','json','--output-file',str(output/'eslint.json'),*js])
run('typecheck', ['bun', 'run', 'typecheck'])
if not app:
    run('android', ['./platforms/android/gradlew', '-p', 'platforms/android', ':runtime:lintDebug', '--max-workers=2'])
    clippy_version=subprocess.check_output(['cargo','clippy','--version'],text=True).strip()
    if clippy_version!='clippy 0.1.95 (59807616e1 2026-04-14)':
        print('Required Clippy 0.1.95 (59807616e1 2026-04-14)',file=sys.stderr)
        rows.append(('clippy-version',2))
    else:
        run('clippy', ['cargo', 'clippy', '--workspace', '--all-targets', '--', '--no-deps', '-D', 'warnings'])
    py = [p for p in owned if p.endswith('.py')]
    if py:
        run('ruff', ['uv', 'tool', 'run', '--from', 'ruff==0.14.5', 'ruff', 'check','--output-format=json','--output-file',str(output/'ruff.json'), *py])
    shell = [p for p in owned if p.endswith('.sh') or p=='test-gate']
    if shell:
        version = subprocess.check_output(['shellcheck', '--version'], text=True)
        if 'version: 0.11.0' not in version:
            print('Required ShellCheck 0.11.0', file=sys.stderr)
            rows.append(('shellcheck-version', 2))
        else:
            with (output/'shellcheck.json').open('w') as handle:
                code=subprocess.call(['shellcheck','--format=json',*shell],cwd=ROOT,stdout=handle)
            rows.append(('shellcheck',code))
    databases = list((ROOT / 'platforms/android').glob('**/compile_commands.json')) + list((ROOT / '.cache/test-gate').glob('**/compile_commands.json'))
    if not databases:
        print('clang-tidy: missing compile_commands.json; native analysis is not verified', file=sys.stderr)
        rows.append(('clang-tidy', 2))
    else:
        version = subprocess.check_output(['clang-tidy', '--version'], text=True)
        if 'LLVM version 23.1.4' not in version:
            print('Required clang-tidy 23.1.4', file=sys.stderr)
            rows.append(('clang-tidy-version', 2))
        else:
            seen = set()
            for database in databases:
                for entry in json.loads(database.read_text()):
                    source = pathlib.Path(entry['file'])
                    source = source if source.is_absolute() else pathlib.Path(entry['directory']) / source
                    context = (str(source), tuple(entry.get('arguments', [entry.get('command', '')])))
                    if context in seen or not source.is_relative_to(ROOT) or 'vendor' in source.parts:
                        continue
                    seen.add(context)
                    run('clang-tidy:' + str(source.relative_to(ROOT)), ['clang-tidy', '-p', str(database.parent), str(source), '--warnings-as-errors=*'])
for report in (ROOT/'platforms/android/runtime/build/reports').glob('lint-results-debug.*'):
    shutil.copy2(report,output/report.name)
(output/'results.json').write_text(json.dumps(rows,indent=2)+'\n')
if not app:
    native_sources={str((ROOT/name).resolve()) for name in files if name.endswith(('.cpp','.cc')) and name.startswith(('platforms/','tests/','crates/'))}
    covered=set()
    for database in databases:
        for entry in json.loads(database.read_text()):
            path=pathlib.Path(entry['file'])
            covered.add(str((path if path.is_absolute() else pathlib.Path(entry['directory'])/path).resolve()))
    missing=sorted(native_sources-covered)
    (output/'native-coverage.json').write_text(json.dumps({'covered':sorted(covered),'missing':missing},indent=2))
    if missing:
        print('clang-tidy: missing contexts for '+str(len(missing))+' owned translation units',file=sys.stderr)
        rows.append(('clang-tidy-contexts',2))
    (output/'results.json').write_text(json.dumps(rows,indent=2)+'\n')
print('lint results: ' + json.dumps(rows))
sys.exit(next((code for _, code in rows if code), 0))
