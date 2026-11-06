#!/usr/bin/env python3
"""Read-only DCPK v1/APK inventory; output JSON to stdout. No extraction."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import struct
import zipfile


def digest(data):
    return hashlib.sha256(data).hexdigest()


def analyze(pak):
    if len(pak) < 32:
        raise ValueError('truncated DCPK header')
    magic, version, flags, count, directory, names, payload, length, reserved = struct.unpack_from('<IHH6I', pak)
    if (magic, version, flags, reserved, length) != (0x4b504344, 1, 0, 0, len(pak)):
        raise ValueError('unsupported or invalid DCPK v1 header')
    if directory != 32 or names != directory + count * 24 or not names <= payload <= length:
        raise ValueError('invalid directory ranges')
    entries, groups, totals = [], collections.defaultdict(list), collections.Counter()
    for i in range(count):
        key_hash, offset, size, name_offset, name_size, dtype, entry_flags, entry_reserved = struct.unpack_from('<4IHBBI', pak, directory + i * 24)
        if names + name_offset + name_size > payload or offset < payload or offset + size > length:
            raise ValueError('entry range outside declared region')
        key = pak[names + name_offset:names + name_offset + name_size].decode('utf-8')
        data = pak[offset:offset + size]
        category = key.split('.')[0]
        use = {
            'ui:font-source': 'conditional: source TTF on first non-seed glyph raster',
            'ui:font': 'conditional: atlas metrics/seeds when its font slot is used',
            'ui:img': 'conditional: image uploaded when its name is requested',
            'ui:styles': 'yes: style table at surface boot',
            'ui:sprite': 'conditional: sprite requested by guest',
            'ui:tile': 'conditional: tiles requested by guest',
        }.get(category, 'unknown: inspect the app and runtime consumer')
        row = dict(key=key, offset=offset, bytes=size, dtype=dtype, type=category, sha256=digest(data), runtimeUse=use, observation='static consumer classification; not a runtime trace')
        if category == 'ui:font' and len(data) >= 64 and data[:4] == b'DCFA':
            v, glyphs = struct.unpack_from('<HH', data, 4)
            row.update(fontVersion=v, glyphs=glyphs, cellWidth=data[8], cellHeight=data[9], density=data[14])
            if v in (5, 6):
                row.update(sourceSha256=data[16:48].hex(), seedCount=struct.unpack_from('<I', data, 56)[0])
        entries.append(row)
        groups[(size, row['sha256'])].append(key)
        totals[category] += size
    return dict(bytes=len(pak), directoryBytes=payload, entries=entries, totals=dict(totals), duplicates=[dict(bytes=k[0], sha256=k[1], keys=v) for k, v in groups.items() if len(v) > 1])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    args = parser.parse_args()
    result = dict(input=str(args.input), bytes=args.input.stat().st_size)
    if zipfile.is_zipfile(args.input):
        with zipfile.ZipFile(args.input) as archive:
            result['zipEntries'] = [dict(name=e.filename, bytes=e.file_size, storedBytes=e.compress_size, compression=e.compress_type) for e in archive.infolist()]
            result['abis'] = sorted({e.filename.split('/')[1] for e in archive.infolist() if e.filename.startswith('lib/') and e.filename.endswith('.so')})
            result['pak'] = analyze(archive.read('assets/main.pak'))
            font_sources = {r['sha256']:r['key'] for r in result['pak']['entries'] if r['type'] == 'ui:font-source'}
            result['duplicatePackagedAssets'] = []
            for entry in archive.infolist():
                if entry.filename.startswith('assets/pod-assets/'):
                    sha = digest(archive.read(entry))
                    if sha in font_sources:
                        result['duplicatePackagedAssets'].append(dict(asset=entry.filename, pakKey=font_sources[sha], sha256=sha, bytes=entry.file_size, storedBytes=entry.compress_size))
    else:
        result['pak'] = analyze(args.input.read_bytes())
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
