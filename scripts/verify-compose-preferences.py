#!/usr/bin/env python3
"""Fail when any frozen Compose preference writer changes without a contract review.

No source database, credentials or external services are opened by this gate.
"""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CALL = re.compile(r"getSharedPreferences\s*\(|EncryptedSharedPreferences\.create\s*\(|[a-zA-Z]+PreferencesKey\s*\(|preferencesDataStoreFile\s*\(|\.put(?:StringSet|String|Long|Boolean|Int|Float)\s*\(")


def verify(source, contract):
    actual = {}
    for top in ("app/src/main", "sdk/bili/src/main", "sdk/douyin/src/main"):
        for path in (source / top).rglob("*.kt"):
            if CALL.search(path.read_text()):
                actual[str(path.relative_to(source))] = hashlib.sha256(path.read_bytes()).hexdigest()
    reviewed = contract["sourceFiles"]
    errors = ["new preference writer: " + name for name in sorted(set(actual) - set(reviewed))]
    # A key can also be introduced through an indirect caller (e.g. a new TTS
    # provider passed to an existing dynamic-key helper). Bind the whole frozen
    # producer, including uncommitted/new main sources, not just direct writers.
    if contract.get("sourceRevision") and (source / ".git").exists():
        def git(*args):
            return subprocess.run(["git", "--no-optional-locks", "-C", str(source), *args],
                                  text=True, capture_output=True, check=True).stdout.strip()
        if git("rev-parse", "HEAD") != contract["sourceRevision"]:
            errors.append("frozen Compose revision changed: review the complete key catalogue")
        roots = ("app/src/main", "sdk/bili/src/main", "sdk/douyin/src/main")
        if git("diff", "--name-only", contract["sourceRevision"], "--", *roots) or git("ls-files", "--others", "--exclude-standard", "--", *roots):
            errors.append("frozen Compose source changed: indirect key callers require catalogue review")
    for name, digest in reviewed.items():
        path = source / name
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            errors.append("unreviewed preference writer change: " + name)
    rows = contract["keys"]
    for row in rows:
        if row["policy"] not in ("mapped", "retained") or not row.get("target"):
            errors.append("missing mapping/preservation policy: " + row["store"] + "/" + row["key"])
    return errors


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    args = parser.parse_args()
    contract = json.loads((ROOT / "platforms/android/runtime/src/main/resources/dev/podjs/runtime/compose-preferences.json").read_text())
    failures = verify(args.source, contract)
    for failure in failures:
        print(failure)
    print(json.dumps({"pass": not failures, "keysOrFamilies": len(contract["keys"]), "reviewedFiles": len(contract["sourceFiles"])}))
    raise SystemExit(bool(failures))
