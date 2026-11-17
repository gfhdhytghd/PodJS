#!/usr/bin/env python3
"""Complete local unit/lint gate. Artifacts and original exit codes are retained."""
import argparse
import concurrent.futures
import hashlib
import json
import os
import pathlib
import shutil
import signal
import subprocess
import sys
import time
from unit_inventory import expand, unlisted_units
entered=time.monotonic()
run_started_wall_ns=time.time_ns()
ROOT = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--project', type=pathlib.Path, default=ROOT)
parser.add_argument('--repo-only', action='store_true')
mode=parser.add_mutually_exclusive_group()
mode.add_argument('--tests-only', action='store_true')
mode.add_argument('--lint-only', action='store_true')
parser.add_argument('--fresh', action='store_true', help='recompile test helpers and execute Gradle tests, keeping dependency builds incremental')
parser.add_argument('--workers', type=int, choices=(1, 2), default=2)
parser.add_argument('--output', type=pathlib.Path)
parser.add_argument('--inside', action='store_true', help=argparse.SUPPRESS)
args = parser.parse_args()
project = args.project.resolve()
app = project != ROOT
label='unit-checks' if args.tests_only else 'lint-checks' if args.lint_only else ('app-repo-checks' if app and args.repo_only else 'test-gate')
out = (args.output or project / '.cache/test-gate/runs' / time.strftime('%Y%m%d-%H%M%S')).resolve()
out.mkdir(parents=True, exist_ok=True)
if not args.inside:
    projects = [ROOT, project] if app and (not args.repo_only) else [project]
    results = []
    for p in projects:
        requested = time.monotonic()
        childout = out / p.name
        childout.mkdir(parents=True, exist_ok=True)
        command = ['flock', '/tmp/wrss-heavy-' + p.name + '.lock', 'capmem', '8G', sys.executable, __file__, '--project', str(p), '--inside', '--repo-only', '--workers', str(args.workers), '--output', str(childout)]
        for enabled, flag in ((args.tests_only, '--tests-only'), (args.lint_only, '--lint-only'), (args.fresh, '--fresh')):
            if enabled:
                command.append(flag)
        env = dict(os.environ, PODJS_QUEUE_STARTED=str(requested),PODJS_QUEUE_AT=str(time.time()))
        result = subprocess.run(command, env=env)
        results.append({'project': str(p), 'exit': result.returncode, 'seconds_including_queue': time.monotonic() - requested,'unit_checked':not args.lint_only,'lint_checked':not args.tests_only})
    (out / 'gate.json').write_text(json.dumps(results, indent=2) + '\n')
    code = next((r['exit'] for r in results if r['exit']), 0)
    print(label+':', 'PASS' if code == 0 else 'FAIL', out)
    sys.exit(code if code >= 0 else 128 - code)
def terminate(signum,frame):
    raise KeyboardInterrupt
signal.signal(signal.SIGTERM,terminate)
env = dict(os.environ, PYTHONDONTWRITEBYTECODE='1', CARGO_BUILD_JOBS='4', PODJS_TIMING_FILE=str(out / 'compile.jsonl'), PODJS_TEST_FRESH='1' if args.fresh else '0', PODJS_TEST_WORKERS=str(args.workers),PODJS_LINT_OUTPUT=str(out/'lint-artifacts'),PODJS_UNIT_CHAIN_REPORT=str(out/'native-chain.jsonl'),PODJS_GRADLE_BUILD_CACHE=str(ROOT/'.cache/test-gate/gradle-build-cache'))
env['PATH'] = str(ROOT / 'scripts/test-tools/bin') + os.pathsep + env['PATH']
gradle_home = ROOT / '.cache/test-gate/gradle-home'
gradle_home.mkdir(parents=True, exist_ok=True)
original = pathlib.Path(os.environ.get('GRADLE_USER_HOME', pathlib.Path.home() / '.gradle'))
for name in ('caches', 'wrapper','gradle.properties'):
    dest = gradle_home / name
    if (original/name).exists() and not dest.is_symlink() and not dest.exists():
        dest.symlink_to(original / name, target_is_directory=(original/name).is_dir())
env['GRADLE_USER_HOME'] = str(gradle_home)
init = gradle_home / 'init.d'
init.mkdir(exist_ok=True)
(init / 'test-gate.gradle').write_text("import groovy.json.JsonOutput\nimport org.gradle.api.tasks.testing.Test\ngradle.settingsEvaluated { settings -> settings.buildCache { local { directory = new File(System.getenv('PODJS_GRADLE_BUILD_CACHE')) } } }\ndef starts = new java.util.concurrent.ConcurrentHashMap()\nallprojects { tasks.withType(Test).configureEach {\n    outputs.upToDateWhen { false }\n    outputs.cacheIf { false }\n    maxParallelForks = 1\n    maxHeapSize = '512m'\n} }\ngradle.taskGraph.beforeTask { task -> starts[task.path] = System.nanoTime() }\ngradle.taskGraph.afterTask { task, state ->\n    def row = [phase:(task instanceof Test ? 'test' : (task.name.startsWith('lint') ? 'lint' : 'build')), tool:'gradle', task:task.path,\n      command:System.getenv('PODJS_COMMAND_NAME'), skipped:state.skipped,\n      seconds:(System.nanoTime() - starts[task.path])/1e9, exit:state.failure == null ? 0 : 1]\n    def target = new File(System.getenv('PODJS_TIMING_FILE'))\n    synchronized (starts) { target.append(JsonOutput.toJson(row) + '\\n') }\n}\n")
env['GRADLE_OPTS'] = env.get('GRADLE_OPTS', '') + ' -Dorg.gradle.jvmargs=-Xmx2g -Dorg.gradle.daemon=true -Dorg.gradle.caching=true -Dorg.gradle.workers.max=2 -Dorg.gradle.daemon.idletimeout=60000'
rows = [{'name': 'queue', 'phase': 'queue', 'seconds': entered - float(os.environ.get('PODJS_QUEUE_STARTED', entered)), 'exit': 0}]
def resources():
    group=next((line.split(':',2)[2].strip() for line in pathlib.Path('/proc/self/cgroup').read_text().splitlines() if line.startswith('0::')), '')
    result={'cgroup':group,'owner_pid':os.getpid()}
    for key in ('memory.max','memory.peak','memory.swap.max'):
        path=pathlib.Path('/sys/fs/cgroup')/group.lstrip('/')/key
        if path.exists():
            result[key]=path.read_text().strip()
    return result
(out/'resources-start.json').write_text(json.dumps(resources(),indent=2))
rows[0].update(requested_at=os.environ.get('PODJS_QUEUE_AT'),acquired_at=time.time(),owner_pid=os.getpid())
start = entered
manifest = json.loads((ROOT / 'scripts/unit-commands.json').read_text())
with (out/'setup.log').open('w') as log:
    ready=subprocess.run(['bun',str(ROOT/'scripts/link-pocket.ts')],cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT).returncode
    if ready==0:
        ready=subprocess.run(['bun',str(ROOT/'examples/watch-notebook/scripts/link-framework.ts')],cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT).returncode
    if ready==0:
        ready=subprocess.run(['bun','-e',"for(const name of ['@babel/core','@babel/preset-typescript','babel-preset-solid','solid-js'])Bun.resolveSync(name,process.cwd());"],cwd=ROOT/'vendor/pocketjs',env=env,stdout=log,stderr=subprocess.STDOUT).returncode
    if ready==0 and app:
        ready=subprocess.run(['bun','scripts/link-framework.ts','--check'],cwd=project,env=env,stdout=log,stderr=subprocess.STDOUT).returncode
if ready:
    rows.append({'name':'setup','phase':'setup','seconds':time.monotonic()-start,'exit':ready})
    (out/'timings.json').write_text(json.dumps(rows,indent=2))
    print(label+': FAIL setup; see '+str(out/'setup.log'),file=sys.stderr)
    sys.exit(ready)

style=ROOT/'vendor/pocketjs/framework/src/styles.generated.ts'
if not args.lint_only and not style.is_file():
    rows.append({'name':'setup','phase':'setup','seconds':time.monotonic()-start,'exit':2})
    (out/'timings.json').write_text(json.dumps(rows,indent=2))
    print('test-gate: missing PocketJS generated style mirror; see scripts/test-gate.md',file=sys.stderr)
    sys.exit(2)
if not args.lint_only:
    reduced=[name for name in ('RESTOREOBJ_SCENARIO','RESTOREOBJ_MIB','RESTOREPATH_BASELINE','MIGRATION_RECOVERY_ONLY') if env.get(name) not in (None,'','0')]
    if reduced:
        rows.append({'name':'setup','phase':'setup','seconds':time.monotonic()-start,'exit':2})
        (out/'timings.json').write_text(json.dumps(rows,indent=2))
        print(label+': refusing reduced test workload: '+', '.join(reduced),file=sys.stderr)
        sys.exit(2)
commands = []
if not args.lint_only:
    if app:
        commands.append(('app-bun', ['bun', 'test', '--timeout=30000', '--path-ignore-patterns=artifacts/**'], False))
        for folder in manifest['appPython']:
            commands.append(('python-' + folder.replace('/', '-'), [sys.executable, '-m', 'pytest', '-q', folder, '--junitxml=' + str(out / (folder.replace('/', '-') + '.xml'))], False))
        commands.append(('offline-selftest', [sys.executable, 'scripts/device/selftest.py'], False))
    else:
        commands.extend(((f'unit-{i:02d}', item['argv'], item['parallel']) for i, item in enumerate(manifest['framework'])))
        commands.extend(((f'extra-{i:02d}', item['argv'], False) for i, item in enumerate(unlisted_units(ROOT,manifest['framework']))))
if not args.tests_only:
    commands.append(('lint', ['bun', 'run', 'lint'], False))
provenance = {'project': str(project), 'head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=project, text=True).strip(), 'fresh': args.fresh, 'workers': args.workers, 'tools': {}}
for tool, flag in [('bun', '--version'), ('java', '-version'), ('cargo', '--version')]:
    result = subprocess.run([tool, flag], capture_output=True, text=True)
    provenance['tools'][tool] = '\n'.join(line for line in (result.stdout + result.stderr).splitlines() if not line.startswith(('Picked up ','NOTE: Picked up '))).strip()
for p in ('package.json', 'bun.lock', 'framework.lock.json', 'scripts/unit-commands.json'):
    path = project / p
    if path.is_file():
        provenance[p] = hashlib.sha256(path.read_bytes()).hexdigest()
owned=hashlib.sha256()
paths=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=project).decode().split('\0')
for name in sorted(set(paths)):
    path=project/name
    if name.startswith(('src/','tests/','packages/','scripts/','platforms/','crates/')) and path.is_file() and path.suffix in ('.ts','.tsx','.js','.mjs','.cjs','.java','.kt','.cpp','.hpp','.h','.c','.rs','.py','.sh','.toml','.kts','.gradle','.xml','.json','.yml','.yaml','.ets'):
        owned.update(name.encode())
        owned.update(path.read_bytes())
provenance['owned_source_sha256']=owned.hexdigest()
if style.is_file():
    provenance['pocketjs_generated_styles_sha256']=hashlib.sha256(style.read_bytes()).hexdigest()
provenance['pocketjs_head']=subprocess.check_output(['git','-C',str(ROOT/'vendor/pocketjs'),'rev-parse','HEAD'],text=True).strip()
(out / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')

rows.append({'name': 'setup', 'phase': 'setup', 'seconds': time.monotonic() - start, 'exit': 0})
def run(item):
    name, command, _ = item
    command = expand(command,project)
    if '-m' in command and 'pytest' in command and not any(arg.startswith('--junitxml') for arg in command):
        command+=['--junitxml='+str(out/(name+'.xml'))]
    if command[0] == 'bun' and 'test' in command:
        command += ['--reporter=junit', '--reporter-outfile=' + str(out / (name + '.xml'))]
    begin = time.monotonic()
    command_started_wall_ns=time.time_ns()
    with (out / (name + '.log')).open('w') as log:
        child = subprocess.Popen(command, cwd=project, env=dict(env, PODJS_COMMAND_NAME=name,PODJS_SWIFT_REPORT=str(out/(name+'.xml'))), stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            code = child.wait(timeout=7200)
        except (subprocess.TimeoutExpired, KeyboardInterrupt) as error:
            os.killpg(child.pid, signal.SIGTERM)
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGKILL)
                child.wait()
            if isinstance(error,KeyboardInterrupt):
                raise
            log.write('\n[test-gate] command watchdog expired; owned process group terminated\n')
            code = 124
    finished=time.monotonic()
    if not app:
        for p in (ROOT / 'platforms/android').glob('*/build/test-results/**/*.xml'):
            if p.stat().st_mtime_ns >= command_started_wall_ns:
                dest = out / (name + '-gradle-xml') / p.relative_to(ROOT / 'platforms/android')
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(p, dest)
    build = 0.0
    setup = 0.0
    if (out / 'compile.jsonl').exists():
        for line in (out / 'compile.jsonl').read_text().splitlines():
            try:
                r = json.loads(line)
            except json.JSONDecodeError:
                continue
            if r.get('command') == name and r.get('phase') == 'build':
                build += r['seconds']
            if r.get('command') == name and r.get('phase') == 'setup':
                setup += r['seconds']
    native_rows=[]
    if (out/'native-chain.jsonl').exists():
        native_rows=[json.loads(line) for line in (out/'native-chain.jsonl').read_text().splitlines() if line.strip()]
        native_rows=[r for r in native_rows if r.get('command')==name]
    if native_rows:
        build=sum(r['seconds'] for r in native_rows if r['phase']=='build')
        setup=sum(r['seconds'] for r in native_rows if r['phase']=='setup')
    row = {'started_monotonic':begin,'finished_monotonic':finished,'build_seconds': build, 'setup_seconds':setup,'test_seconds': max(0, finished - begin - build - setup), 'name': name, 'phase': 'lint' if name == 'lint' else 'test', 'command': command, 'seconds': finished - begin,'collect_seconds':time.monotonic()-finished, 'exit': code}
    print(name, round(row['seconds'], 3), 'exit=' + str(code), flush=True)
    return row
try:
    batch = []

    def flush():
        if batch:
            with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
                rows.extend(pool.map(run, batch))
            batch.clear()
    for item in commands:
        if item[2]:
            batch.append(item)
        else:
            flush()
            rows.append(run(item))
        (out / 'timings.json').write_text(json.dumps(rows, indent=2) + '\n')
    flush()
finally:
    if not app:
        with (out / 'gradle-stop.log').open('w') as log:
            try:
                cleanup=subprocess.run([str(ROOT / 'platforms/android/gradlew'), '--stop'], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=60).returncode
            except subprocess.TimeoutExpired:
                cleanup=124
        stopped=[]
        own_group=pathlib.Path('/proc/self/cgroup').read_text()
        for path in gradle_home.glob('daemon/*/daemon-*.out.log'):
            pid=int(path.name.removeprefix('daemon-').removesuffix('.out.log'))
            proc=pathlib.Path('/proc')/str(pid)
            try:
                if (proc/'cgroup').read_text()!=own_group or b'GradleDaemon' not in (proc/'cmdline').read_bytes():
                    continue
                os.kill(pid,signal.SIGTERM)
                deadline=time.monotonic()+5
                while proc.exists() and time.monotonic()<deadline:
                    time.sleep(0.05)
                if proc.exists() and (proc/'cgroup').read_text()==own_group:
                    os.kill(pid,signal.SIGKILL)
                stopped.append(pid)
            except (ProcessLookupError,FileNotFoundError):
                pass
        (out/'daemon-cleanup.json').write_text(json.dumps({'stop_exit':cleanup,'fallback_pids':stopped}))
    for p in (project / 'platforms/android').glob('*/build/test-results/**/*.xml'):
        if p.stat().st_mtime_ns < run_started_wall_ns:
            continue
        dest = out / 'gradle-xml' / p.relative_to(project / 'platforms/android')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(p, dest)
    (out / 'timings.json').write_text(json.dumps(rows, indent=2) + '\n')
    (out/'resources-end.json').write_text(json.dumps(resources(),indent=2))
code = next((r['exit'] for r in rows if r['exit']), 0)
print(label+':', 'PASS' if code == 0 else 'FAIL', out)
sys.exit(code if code >= 0 else 128 - code)
