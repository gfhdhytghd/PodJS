"""Conservative fallback for existing/new owned units absent from old chains."""
import glob
import pathlib
import re
import subprocess


def expand(argv, root):
    result = []
    for arg in argv:
        matches = sorted(glob.glob(arg, root_dir=root)) if '*' in arg else []
        result.extend(matches or [arg])
    return result


def unlisted_units(root, commands):
    tracked = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=root).decode().split('\0')
    tests = [p for p in tracked if p.startswith(('packages/', 'tests/')) and p.endswith(('.test.ts','.test.tsx','.spec.ts','.spec.tsx','.test.js','.test.mjs','.spec.js','.spec.mjs','.isolated.ts'))]
    known = set()
    for command in commands:
        argv = expand(command['argv'], root)
        for arg in argv:
            if arg.endswith(('.test.ts','.test.tsx','.spec.ts','.spec.tsx','.test.js','.test.mjs','.spec.js','.spec.mjs','.isolated.ts')):
                known.add(arg.removeprefix('./'))
            elif (root / arg).is_dir() and arg.endswith('/tests'):
                known.update(str(p.relative_to(root)) for p in (root / arg).rglob('*.test.ts'))
        if argv[0] == 'bash' and len(argv) > 1 and argv[1].startswith('scripts/'):
            source = (root / argv[1]).read_text()
            known.update(p.removeprefix('./') for p in re.findall(r'[\w./-]+\.(?:test|isolated)\.ts', source))
    # Isolated suites explicitly delegated by parents remain delegated, so
    # their realm/preloads/conditions and the parent assertions stay intact.
    sources = {p: (root / p).read_text() for p in tests}
    for p in tests:
        if p.endswith('.isolated.ts') and any(pathlib.Path(p).name in text for other, text in sources.items() if other != p):
            known.add(p)
    result = []
    for p in sorted(set(tests) - known):
        argv = ['bun', '--conditions=browser', 'test', './' + p] if p.endswith('.isolated.ts') else ['bun', 'test', './' + p]
        result.append({'argv': argv, 'parallel': False, 'origin': 'owned unit discovery fallback'})
    return result
