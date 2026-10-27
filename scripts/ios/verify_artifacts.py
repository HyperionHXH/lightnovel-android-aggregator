#!/usr/bin/env python3
"""Read-only delivery validator: integrity is not equivalent to visual acceptance."""
import argparse
import hashlib
import json
import pathlib
import plistlib
import subprocess
import zipfile


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_tour(root, ffprobe):
    data = json.loads((root / 'manifest.json').read_text(encoding='utf-8'))
    if data.get('xcodeExit') != 0 or data.get('errors'):
        raise ValueError('UI test or capture host failed')
    captures = data['captures']
    if [c['name'] for c in captures] != data['expected']:
        raise ValueError('Incomplete or reordered route evidence')
    if len({c['sha256'] for c in captures}) != len(captures):
        raise ValueError('Duplicate screenshot frames')
    from PIL import Image
    for i, item in enumerate(captures):
        path = (root / item['file']).resolve()
        if root.resolve() not in path.parents or not path.is_file():
            raise ValueError('Invalid screenshot path')
        if item['order'] != i + 1 or path.stat().st_size != item['bytes'] or sha(path) != item['sha256']:
            raise ValueError('Screenshot bytes/hash/order mismatch')
        with Image.open(path) as image:
            if list(image.size) != [item['width'], item['height']] or min(image.size) < 300:
                raise ValueError('Screenshot geometry mismatch')
            image.verify()
    video = data['video']
    path = root / video['file']
    if path.stat().st_size != video['bytes'] or sha(path) != video['sha256']:
        raise ValueError('Recording integrity mismatch')
    probe = json.loads(subprocess.check_output([ffprobe, '-v', 'error', '-count_frames', '-select_streams', 'v:0',
                                               '-show_entries', 'stream=width,height,nb_read_frames:format=duration',
                                               '-of', 'json', str(path)], text=True))
    stream = probe['streams'][0]
    if int(stream['nb_read_frames']) < 24 or float(probe['format']['duration']) < 3 or min(stream['width'], stream['height']) < 300:
        raise ValueError('Recording has insufficient decodable video')
    print(f'Integrity verified: {len(captures)} native screenshots; {stream["nb_read_frames"]} video frames; {probe["format"]["duration"]}s')


def verify_ipa(path):
    with zipfile.ZipFile(path) as archive:
        if archive.testzip():
            raise ValueError('Corrupt IPA')
        plist_paths = [n for n in archive.namelist() if n.startswith('Payload/') and n.count('/') == 2 and n.endswith('/Info.plist')]
        if len(plist_paths) != 1:
            raise ValueError('IPA must contain one device application')
        info = plistlib.loads(archive.read(plist_paths[0]))
        executable = plist_paths[0].rsplit('/', 1)[0] + '/' + info['CFBundleExecutable']
        if executable not in archive.namelist() or info['CFBundleIdentifier'] != 'io.github.hyperionhxh.mixn':
            raise ValueError('Wrong IPA executable/bundle')
        if info.get('CFBundleSupportedPlatforms') != ['iPhoneOS']:
            raise ValueError('Simulator app is not a deliverable IPA')
        forbidden = ['embedded.mobileprovision', '_CodeSignature/CodeResources']
        if any(n.endswith(f) for f in forbidden for n in archive.namelist()):
            raise ValueError('Unexpected signing material in unsigned IPA')
    digest = sha(path)
    expected = path.with_suffix(path.suffix + '.sha256').read_text(encoding='utf-8').split()[0]
    if digest != expected:
        raise ValueError('Published checksum does not match IPA')
    print(f'Unsigned IPA verified: {info["CFBundleShortVersionString"]}({info["CFBundleVersion"]}), {path.stat().st_size} bytes, sha256={digest}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--tour', type=pathlib.Path)
    parser.add_argument('--ipa', type=pathlib.Path)
    parser.add_argument('--ffprobe', default='ffprobe')
    args = parser.parse_args()
    if not args.tour and not args.ipa:
        parser.error('--tour and/or --ipa required')
    if args.tour:
        verify_tour(args.tour, args.ffprobe)
    if args.ipa:
        verify_ipa(args.ipa)
