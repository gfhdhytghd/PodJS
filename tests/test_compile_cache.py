"""Regressions for javac cache invalidation without path or timestamp changes."""
import json
import os
from pathlib import Path
import shutil
import subprocess
from zipfile import ZipFile

import pytest


ROOT = Path(__file__).resolve().parents[1]
WRAPPER = ROOT / "scripts/test-tools/bin/javac"


@pytest.mark.parametrize(("kind", "classpath_option"), [
    ("directory", None),
    ("jar", None),
    ("jar", "--class-path"),
])
def test_javac_invalidates_replaced_classpath_dependency(tmp_path, kind, classpath_option):
    path = os.pathsep.join(
        entry for entry in os.get_exec_path()
        if Path(entry).resolve() != WRAPPER.parent
    )
    javac = shutil.which("javac", path=path)
    assert javac is not None

    classes = tmp_path / "classes"
    dependency = tmp_path / "Dependency.java"
    caller = tmp_path / "Caller.java"
    caller.write_text("class Caller { int value = Dependency.VALUE; }\n")
    classpath = classes if kind == "directory" else tmp_path / "dependency.jar"
    dependency_file = classes / "Dependency.class" if kind == "directory" else classpath
    timing = tmp_path / "compile.jsonl"
    env = {**os.environ, "PATH": path, "PODJS_TEST_CACHE": str(tmp_path / "cache"),
           "PODJS_TIMING_FILE": str(timing), "PODJS_TEST_FRESH": "0", "LC_ALL": "C"}
    for key in ("CLASSPATH", "JDK_JAVAC_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS"):
        env.pop(key, None)
    options = []
    if classpath_option:
        env["CLASSPATH"] = str(tmp_path / "unused")
        options.append(f"{classpath_option}={classpath}")
    else:
        env["CLASSPATH"] = str(classpath)

    def build_dependency(body):
        dependency.write_text(f"class Dependency {{ {body} }}\n")
        subprocess.run([javac, "-d", classes, dependency], check=True, cwd=tmp_path, env=env)
        if kind == "jar":
            with ZipFile(classpath, "w") as archive:
                archive.write(classes / "Dependency.class", "Dependency.class")

    def compile_caller(compiler, name, *, fresh=False):
        output = tmp_path / name
        result = subprocess.run(
            [compiler, *options, "-d", output, caller],
            cwd=tmp_path,
            env={**env, "PODJS_TEST_FRESH": "1" if fresh else "0"},
            capture_output=True,
            text=True,
        )
        return result, output / "Caller.class"

    build_dependency("static final int VALUE = 7;")
    original = dependency_file.stat()
    first, first_class = compile_caller(WRAPPER, "first")
    assert first.returncode == 0, first.stderr
    warm, warm_class = compile_caller(WRAPPER, "warm")
    assert warm.returncode == 0, warm.stderr
    assert warm_class.read_bytes() == first_class.read_bytes()

    build_dependency("static final int OTHER = 7;")
    assert dependency_file.stat().st_size == original.st_size
    os.utime(dependency_file, ns=(original.st_atime_ns, original.st_mtime_ns))
    changed, changed_class = compile_caller(WRAPPER, "changed")
    direct, _ = compile_caller(javac, "direct")
    fresh, _ = compile_caller(WRAPPER, "fresh", fresh=True)

    assert changed.returncode == direct.returncode == fresh.returncode == 1
    assert "cannot find symbol" in changed.stderr and "VALUE" in changed.stderr
    assert changed.stderr == direct.stderr == fresh.stderr
    assert not changed_class.exists()
    cold_record, warm_record, change_record, fresh_record = [
        json.loads(line) for line in timing.read_text().splitlines()
    ]
    assert not cold_record["cache_hit"]
    assert warm_record["cache_hit"]
    assert cold_record["key"] == warm_record["key"]
    assert not change_record["cache_hit"] and not fresh_record["cache_hit"]
    assert change_record["key"] == fresh_record["key"] != cold_record["key"]
