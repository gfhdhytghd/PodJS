#!/usr/bin/env python3
"""Cache successful test compilation only. Tests always execute in private tmp dirs."""
import fcntl
import ctypes
import signal
import threading
import hashlib
import json
import os
import pathlib
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
import stat
import zipfile
import zlib
ROOT = pathlib.Path(__file__).resolve().parents[2]
BIN = pathlib.Path(__file__).resolve().parent / 'bin'
name = pathlib.Path(sys.argv[0]).name
search = os.pathsep.join((p for p in os.get_exec_path() if pathlib.Path(p).resolve() != BIN))
tool = shutil.which(name, path=search)
if not tool:
    print(f'Missing compiler: {name}',file=sys.stderr)
    sys.exit(127)
start = time.monotonic()
args = sys.argv[1:]
def native_context():
    database = pathlib.Path(os.environ.get('PODJS_TEST_CACHE', ROOT / '.cache/test-gate')) / 'native-database'
    database.mkdir(parents=True, exist_ok=True)
    sources = [arg for arg in args if pathlib.Path(arg).suffix in ('.cpp', '.cc', '.c')]
    compile_args = [arg for i, arg in enumerate(args) if arg not in sources and arg not in ('-shared',) and (i not in (args.index('-o'), args.index('-o') + 1)) and (not arg.startswith('-l'))]
    entries = [{'directory': str(pathlib.Path.cwd()), 'file': str(pathlib.Path(source).absolute()), 'arguments': [tool, *compile_args, source, '-c']} for source in sources]
    target = database / hashlib.sha256(json.dumps(entries).encode()).hexdigest()
    target.mkdir(exist_ok=True)
    # Keep ephemeral generated include headers for later native lint, without
    # changing the actual compilation command or the compiler cache key.
    for entry in entries:
        copied=[]
        for i,arg in enumerate(entry['arguments']):
            include = arg[2:] if arg.startswith('-I') and len(arg)>2 else None
            if include is None:
                copied.append(arg)
                continue
            folder=pathlib.Path(include).absolute()
            if folder.is_dir() and folder.is_relative_to(pathlib.Path(tempfile.gettempdir())) and not folder.is_relative_to(ROOT):
                dest=target/'includes'/hashlib.sha256(str(folder).encode()).hexdigest()
                dest.mkdir(parents=True,exist_ok=True)
                for header in folder.rglob('*'):
                    if header.is_file() and header.suffix in ('.h','.hpp','.inc','.def'):
                        output_header=dest/header.relative_to(folder)
                        output_header.parent.mkdir(parents=True,exist_ok=True)
                        shutil.copy2(header,output_header)
                copied.append('-I'+str(dest))
            else:
                copied.append(arg)
        entry['arguments']=copied
    (target/'compile_commands.json').write_text(json.dumps(entries))

if name != 'javac' and '-o' in args and any(a.startswith('-fsanitize=') for a in args):
    native_context()
if any('@' in arg or arg == '-' or arg.startswith(('-L','-Wl,','-M','-save-temps','-fprofile','-fmodule','-fsanitize','-flto','-fplugin','-specs=','--target','-target','--gcc-toolchain','-stdlib=','--sysroot','-isysroot','-B','--module','--processor','--upgrade-module','--patch-module')) or arg in ('-processorpath','-processor','-proc:only','--system','-bootclasspath','-h','-s','--coverage') for arg in args) or any('*' in arg for i,arg in enumerate(args) if i and args[i-1] in ('-cp','-classpath','--class-path')):
    os.execv(tool,[tool,*args])
option = '-d' if name == 'javac' else '-o'
if option not in args:
    os.execv(tool, [tool, *args])
# Isolate only this helper's descendants. Parent death and test timeouts
# forward to this known process group, including compiler grandchildren.
parent_pid=os.getppid()
if os.getpgrp()!=os.getpid():
    os.setsid()
def forward(signum,frame):
    signal.signal(signal.SIGTERM,signal.SIG_IGN)
    signal.signal(signal.SIGINT,signal.SIG_IGN)
    os.killpg(os.getpgrp(),signum)
    raise SystemExit(128+signum)
signal.signal(signal.SIGTERM,forward)
signal.signal(signal.SIGINT,forward)
if ctypes.CDLL(None).prctl(1,signal.SIGTERM,0,0,0)!=0:
    raise RuntimeError('Cannot install compiler parent-death cleanup')
if os.getppid()!=parent_pid:
    forward(signal.SIGTERM,None)
    sys.exit(143)
def execute():
    child=subprocess.Popen([tool,*args],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    buffers=[bytearray(),bytearray()]
    def drain(stream,fd,index):
        while True:
            chunk=stream.read(65536)
            if not chunk:
                break
            buffers[index].extend(chunk)
            os.write(fd,chunk)
    threads=[threading.Thread(target=drain,args=(child.stdout,sys.stdout.fileno(),0)),threading.Thread(target=drain,args=(child.stderr,sys.stderr.fileno(),1))]
    for thread in threads:
        thread.start()
    code=child.wait()
    for thread in threads:
        thread.join()
    return subprocess.CompletedProcess([tool,*args],code,bytes(buffers[0]),bytes(buffers[1]))
def uncached():
    result=execute()
    sys.exit(result.returncode if result.returncode>=0 else 128-result.returncode)

output = pathlib.Path(args[args.index(option) + 1]).absolute()
h = hashlib.sha256()

def add(value):
    h.update(value if isinstance(value, bytes) else str(value).encode())
    h.update(b'\x00')

def file(path):
    p = pathlib.Path(path)
    if p.is_dir():
        for child in sorted(p.rglob('*')):
            if child.is_file():
                add(child.relative_to(p))
                add(child.read_bytes())
    elif p.is_file():
        add(p.read_bytes())
    else:
        add('missing:' + str(p))
JAVA_CLASSPATH_OPTIONS = ('-cp', '-classpath', '--class-path')


def java_classpath():
    """Return javac's effective classpath. A command option overrides the environment."""
    value = os.environ.get('CLASSPATH', '.')
    options = iter(args)
    for arg in options:
        if arg in JAVA_CLASSPATH_OPTIONS:
            value = next(options, None)
        elif arg.startswith('--class-path='):
            value = arg.partition('=')[2]
    return value


def java_classpath_files(directory):
    """Enumerate a bounded tree or decline caching if it cannot be read safely."""
    directories = [directory]
    files = []
    remaining = 4096
    while directories:
        with os.scandir(directories.pop()) as children:
            for child in children:
                remaining -= 1
                if not remaining:
                    return None
                if child.is_dir():
                    # pathlib's recursive glob omits symlinked subdirectories.
                    # Decline rather than miss classes or recurse through a cycle.
                    if child.is_symlink():
                        return None
                    directories.append(child.path)
                elif child.is_file():
                    files.append(pathlib.Path(child.path))
                else:
                    return None
    return sorted(files)


def hash_java_classpath():
    value = java_classpath()
    # Wildcards and injected arguments may expand to additional inputs.
    if value is None or '*' in value or os.environ.get('JDK_JAVAC_OPTIONS'):
        return False

    for part in value.split(os.pathsep):
        # Empty entries in a nonempty path denote the current directory.
        path = pathlib.Path(part)
        add(path.absolute())
        try:
            mode = path.stat().st_mode
        except FileNotFoundError:
            add('missing-classpath-entry')
            continue
        if stat.S_ISDIR(mode):
            files = java_classpath_files(path)
            if files is None:
                return False
            # Broad classpaths such as a repository root can contain build
            # trees and unrelated large artifacts. Compile directly if hashing
            # the whole tree would dominate the invocation.
            if sum(child.stat().st_size for child in files) > 256 * 1024**2:
                return False
            add('classpath-directory')
            add(len(files))
            for child in files:
                add(child.relative_to(path))
                with child.open('rb') as stream:
                    # Javac can prefer a newer source over a matching class.
                    add(os.fstat(stream.fileno()).st_mtime_ns)
                    add(hashlib.file_digest(stream, 'sha256').digest())
        elif stat.S_ISREG(mode):
            # A manifest Class-Path can load jars outside the supplied path.
            # Let javac resolve such archives without reusing a cached result.
            with zipfile.ZipFile(path) as archive:
                for info in archive.infolist():
                    if info.filename.upper() != 'META-INF/MANIFEST.MF':
                        continue
                    if info.file_size > 65536:
                        return False
                    if any(line.lower().startswith(b'class-path:') for line in archive.read(info).splitlines()):
                        return False
            add('classpath-archive')
            add(path.name)
            with path.open('rb') as stream:
                add(hashlib.file_digest(stream, 'sha256').digest())
        else:
            return False
    return True


if name == 'javac':
    try:
        classpath_cacheable = hash_java_classpath()
    except (OSError, ValueError, zipfile.BadZipFile, zlib.error, NotImplementedError, RuntimeError):
        classpath_cacheable = False
    if not classpath_cacheable:
        uncached()

add(pathlib.Path(__file__).read_bytes())
add(pathlib.Path(tool).resolve())
file(pathlib.Path(tool).resolve())
add(subprocess.check_output([tool, '-version' if name == 'javac' else '--version'], stderr=subprocess.STDOUT))
normalized = []
for i, arg in enumerate(args):
    if i == args.index(option) + 1:
        normalized.append('<output>')
        continue
    if name == 'javac' and (
        arg.startswith('--class-path=') or (i and args[i - 1] in JAVA_CLASSPATH_OPTIONS)
    ):
        normalized.append(arg)
        continue
    if i and args[i - 1] in ('-sourcepath', '--source-path'):
        parts = []
        for part in arg.split(os.pathsep):
            p = pathlib.Path(part)
            file(p)
            parts.append('<output>' if p.absolute() == output else '<classpath>')
        normalized.append(os.pathsep.join(parts))
        continue
    p = pathlib.Path(arg)
    if p.is_file():
        file(p)
        normalized.append('<file:' + p.name + '>')
    else:
        normalized.append(arg)
add(json.dumps(normalized))
add(os.environ.get('JAVA_HOME', ''))
add(os.environ.get('JDK_JAVAC_OPTIONS', ''))
add(os.environ.get('CLASSPATH', ''))
add(os.environ.get('JAVA_TOOL_OPTIONS', ''))
add(os.environ.get('_JAVA_OPTIONS', ''))
for key in ('SOURCE_DATE_EPOCH','TZ','LANG','LC_ALL','CPATH','CPLUS_INCLUDE_PATH','LIBRARY_PATH','COMPILER_PATH','GCC_EXEC_PREFIX'):
    add(key)
    add(os.environ.get(key,''))
if name == 'javac':
    jdk=pathlib.Path(tool).resolve().parents[1]
    file(jdk/'lib/modules')
    file(jdk/'release')
    file(jdk/'bin/java')
    for library in sorted((jdk/'lib').rglob('*.so')):
        add(library.relative_to(jdk))
        file(library)
    file(jdk/'lib/jvm.cfg')
else:
    for library in ('libstdc++.so','libgcc_s.so','libc.so','crtbegin.o','cc1plus'):
        location=subprocess.check_output([tool,'-print-file-name='+library],text=True).strip()
        file(pathlib.Path(location).resolve())
for directory in ('platforms/android/runtime/src', 'tests/java', 'tests/cpp', 'crates/podjs-runtime/include'):
    for p in sorted((ROOT / directory).rglob('*')):
        if p.is_file() and p.suffix in ('.java', '.h', '.hpp', '.cpp', '.cc', '.c', '.json', '.xml'):
            add(p.relative_to(ROOT))
            file(p)
if name != 'javac':
    native_context()
    dep_args = args[:]
    at = dep_args.index('-o')
    del dep_args[at:at + 2]
    deps = subprocess.run([tool, *dep_args, '-M'], capture_output=True, text=True)
    if deps.returncode:
        uncached()
    time_dependent=False
    for line in deps.stdout.replace('\\\n', ' ').splitlines():
        if ': ' in line:
            for dep in shlex.split(line.split(': ', 1)[1]):
                add(dep)
                file(dep)
                if pathlib.Path(dep).is_file():
                    data=pathlib.Path(dep).read_bytes()
                    if any(token in data for token in (b'__DATE__',b'__TIME__',b'__TIMESTAMP__')):
                        time_dependent=True
    if time_dependent:
        uncached()
    resource_query=subprocess.run([tool,'-print-resource-dir'],capture_output=True,text=True)
    resource=pathlib.Path(resource_query.stdout.strip()) if resource_query.returncode==0 and resource_query.stdout.strip() else None
    if resource is None or not resource.is_dir():
        include=subprocess.check_output([tool,'-print-file-name=include'],text=True).strip()
        resource=pathlib.Path(include).parent if pathlib.Path(include).is_dir() else None
    if resource is None:
        uncached()
    add(resource.resolve())
    # Transitive header contents above include compiler builtin headers.
    # Hash the actual compiler/linker/runtime inputs, not unrelated toolchain
    # executables that cannot participate in this compile.
    for library in ('libc_nonshared.a','Scrt1.o','crt1.o','crti.o','crtn.o','crtbeginS.o','crtendS.o','libgcc.a','libgcc_eh.a','libc.so.6','libgcc_s.so.1','libm.so.6','libmvec.so.1','libpthread.so.0','libdl.so.2','librt.so.1','ld-linux-x86-64.so.2'):
        location=subprocess.check_output([tool,'-print-file-name='+library],text=True).strip()
        file(pathlib.Path(location).resolve())
    for program in ('ld','as'):
        executable=subprocess.check_output([tool,'-print-prog-name='+program],text=True).strip()
        executable=shutil.which(executable,path=search) or executable
        file(pathlib.Path(executable).resolve())
    for library_name in ('libstdc++.so', 'libgcc_s.so', 'libc.so', 'cc1plus', 'collect2'):
        library = subprocess.check_output([tool, '-print-file-name=' + library_name], text=True).strip()
        file(pathlib.Path(library).resolve())
    for arg in args:
        if arg.startswith('-l'):
            library = subprocess.check_output([tool, '-print-file-name=lib' + arg[2:] + '.so'], text=True).strip()
            file(pathlib.Path(library).resolve())
key = h.hexdigest()
cache = pathlib.Path(os.environ.get('PODJS_TEST_CACHE', ROOT / '.cache/test-gate')) / 'compile' / key
cache.parent.mkdir(parents=True, exist_ok=True)
with (cache.parent / (key + '.lock')).open('w') as lock:
    fcntl.flock(lock, fcntl.LOCK_EX)
    hit = (cache / 'complete').is_file() and (cache / 'output').exists() and (os.environ.get('PODJS_TEST_FRESH') != '1')
    if hit:
        manifest_path=cache/'artifacts.json'
        current={}
        output_files=sorted((cache/'output').rglob('*')) if name=='javac' else [cache/'output']
        for path in output_files:
            if path.is_file():
                relative=str(path.relative_to(cache/'output')) if name=='javac' else 'binary'
                current[relative]=hashlib.sha256(path.read_bytes()).hexdigest()
        hit=manifest_path.is_file() and current==json.loads(manifest_path.read_text())
    if hit:
        if name == 'javac':
            shutil.copytree(cache / 'output', output, dirs_exist_ok=True)
        else:
            output.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(cache / 'output', output)
        for stream,handle in [('stdout',sys.stdout.buffer),('stderr',sys.stderr.buffer)]:
            saved=cache/(stream+'.log')
            if saved.exists():
                handle.write(saved.read_bytes())
                handle.flush()
        code = 0
    else:
        before={str(p.relative_to(output)):(p.stat().st_mtime_ns,p.stat().st_ctime_ns,p.stat().st_size) for p in output.rglob('*.class')} if name=='javac' else {}
        result=execute()
        code=result.returncode
        if code == 0:
            staging = pathlib.Path(tempfile.mkdtemp(prefix=key + '.', dir=cache.parent))
            try:
                if name == 'javac':
                    (staging / 'output').mkdir()
                    for cls in output.rglob('*.class'):
                        stat=cls.stat()
                        if before.get(str(cls.relative_to(output))) == (stat.st_mtime_ns,stat.st_ctime_ns,stat.st_size):
                            continue
                        target = staging / 'output' / cls.relative_to(output)
                        target.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copy2(cls, target)
                else:
                    shutil.copy2(output, staging / 'output')
                manifest={}
                output_files=sorted((staging/'output').rglob('*')) if name=='javac' else [staging/'output']
                for path in output_files:
                    if path.is_file():
                        relative=str(path.relative_to(staging/'output')) if name=='javac' else 'binary'
                        manifest[relative]=hashlib.sha256(path.read_bytes()).hexdigest()
                (staging/'artifacts.json').write_text(json.dumps(manifest,sort_keys=True))
                (staging/'stdout.log').write_bytes(result.stdout)
                (staging/'stderr.log').write_bytes(result.stderr)
                (staging / 'complete').write_text(key)
                if cache.exists():
                    shutil.rmtree(cache)
                staging.rename(cache)
            finally:
                if staging.exists():
                    shutil.rmtree(staging)
record = {'phase': 'build', 'tool': name, 'key': key, 'cache_hit': hit, 'seconds': time.monotonic() - start, 'exit': code, 'command': os.environ.get('PODJS_COMMAND_NAME')}
if os.environ.get('PODJS_TIMING_FILE'):
    with open(os.environ['PODJS_TIMING_FILE'], 'a') as log:
        fcntl.flock(log, fcntl.LOCK_EX)
        log.write(json.dumps(record) + '\n')
sys.exit(code if code >= 0 else 128-code)
