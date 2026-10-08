#!/usr/bin/env python3
"""Protect T5's byte-identical shared recovery implementation."""
import hashlib
import os
from pathlib import Path

framework = Path(__file__).resolve().parents[1] / 'platforms/android/runtime/src/main/java/dev/podjs/takeover/NativeBackup.java'
recovery = Path(os.environ.get('PODJS_RECOVERY_TREE', '/home/wilf/data/OPPO_Develop/Android_WatchRSS.wt-recovery'))
if not recovery.exists():
    print('SKIP: PODJS_RECOVERY_TREE does not exist')
else:
    native = recovery / 'app/src/main/java/dev/podjs/takeover/NativeBackup.java'
    assert hashlib.sha256(framework.read_bytes()).digest() == hashlib.sha256(native.read_bytes()).digest(), 'NativeBackup recovery SHA256 mismatch'
    print('PASS: framework/recovery NativeBackup SHA256 equal')
