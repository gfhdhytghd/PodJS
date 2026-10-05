#!/usr/bin/env python3
"""Only consumes the synthetic directory created by RoomV18Test; no discovery."""
import importlib.util
import io
import json
from pathlib import Path
import sqlite3
import sys
import tarfile

app, fixture = map(Path, sys.argv[1:])
source = fixture / 'oracle-java/room.sqlite'
assert source.is_file() and fixture.name == 'fixture'

def load(name):
    spec = importlib.util.spec_from_file_location(name, app / 'scripts/migration' / (name + '.py'))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod

baseline = fixture / 'baseline.sqlite'
schema = json.loads(Path('platforms/android/runtime/src/main/resources/dev/podjs/runtime/watchrss-schema.json').read_text())
with sqlite3.connect(baseline) as db:
    for statement in schema:
        db.execute(statement)
    db.execute("INSERT INTO sync_local_identity VALUES(1,'fixture','fixture')")
archive = fixture / 'synthetic-files.tar'
with tarfile.open(archive, 'w') as tar:
    for name, value in {
        'shared_prefs/watch_device_identity.xml': '<map><string name="device_id">fixture-设备😀</string></map>',
        'shared_prefs/reader_preset_state.xml': '<map/>',
        'files/article_content/fixture.html': '<p>Synthetic reference, handled in P2d</p>',
    }.items():
        data = value.encode()
        member = tarfile.TarInfo(name)
        member.size = len(data)
        tar.addfile(member, io.BytesIO(data))
expected = fixture / 'oracle-python.sqlite'
load('stage_native_v18').stage(source, baseline, archive, expected, fixture / 'oracle-python-report.json')
load('stage_reader_presets').run(source, archive, fixture / 'oracle-reader.json', fixture / 'oracle-reader-report.json')
with sqlite3.connect(expected) as py, sqlite3.connect(fixture / 'oracle-java/podjs.sqlite') as java:
    tables = [r[0] for r in py.execute("SELECT name FROM sqlite_master WHERE type='table'")]
    assert list(py.execute("SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY name")) == list(java.execute("SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY name")), 'schema drift'
    for table in tables:
        # Revision is deliberately regenerated: source row ordering can produce a
        # different number of idempotent binding writes than the offline script.
        if table == 'sync_library_revision':
            continue
        cols = [r[1] for r in py.execute(f'PRAGMA table_info("{table}")')]
        def rows(db):
            result = []
            for row in db.execute(f'SELECT * FROM "{table}"'):
                values = list(row)
                if table == 'llm_token_usage':
                    i = cols.index('usage_json')
                    values[i] = json.dumps(json.loads(values[i]), sort_keys=True)
                result.append(tuple(values))
            return sorted(result, key=repr)
        assert rows(py) == rows(java), f'oracle differs: {table}'
    assert java.execute('PRAGMA quick_check').fetchone() == ('ok',)
    assert not java.execute('PRAGMA foreign_key_check').fetchall()
    java.execute("INSERT INTO sync_change_log(kind,entity_id,changed_at,origin_device_id,reason,created_at) VALUES('x','x',0,'x','x',0)")
    assert java.execute('SELECT max(seq) FROM sync_change_log').fetchone()[0] == 4
p = json.loads((fixture / 'oracle-reader.json').read_text())
j = json.loads((fixture / 'oracle-java/reader-catalog.json').read_text())
for key in ('records',):
    assert sorted(p[key], key=lambda r: r['id']) == sorted(j[key], key=lambda r: r['id'])
for key in ('fonts', 'backgrounds', 'deletions'):
    assert sorted(p['assets'][key], key=repr) == sorted(j['assets'][key], key=repr), key
print(f'Python oracle: {len(tables)} target tables + 4 reader catalogs, schema and next sequence passed')
