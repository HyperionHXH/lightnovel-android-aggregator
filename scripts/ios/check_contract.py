#!/usr/bin/env python3
"""Validate version/contract inventory, without claiming feature or UI correctness."""
import argparse
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]


def version(root=ROOT):
    android = (root / 'app/build.gradle.kts').read_text(encoding='utf-8')
    name = re.search(r'versionName\s*=.*\?:\s*"([^\"]+)"', android).group(1)
    code = re.search(r'versionCode\s*=.*\?:\s*(\d+)', android).group(1)
    import yaml
    spec = yaml.safe_load((root / 'ios/project.yml').read_text(encoding='utf-8'))
    settings = spec['settings']['base']
    if str(settings['MARKETING_VERSION']) != name or str(settings['CURRENT_PROJECT_VERSION']) != code:
        raise ValueError('Android and iOS versions differ; update both in the same feature change')
    if settings['IPHONEOS_DEPLOYMENT_TARGET'] != '15.0':
        raise ValueError('Changing iOS minimum requires a documented compatibility review')
    return name, code


def check(root=ROOT):
    name, code = version(root)
    cases = json.loads((root / 'contracts/lns-author-cases.json').read_text(encoding='utf-8'))
    if len({x['name'] for x in cases}) != len(cases) or not any(x['author'] is None for x in cases):
        raise ValueError('Missing distinct uploader-only shared author regression')
    toolchain = json.loads((root / 'ios/toolchain.json').read_text(encoding='utf-8'))
    if not re.fullmatch(r'[a-f0-9]{64}', toolchain['xcodegenSha256']):
        raise ValueError('XcodeGen download must be pinned by checksum')
    print(f'Cross-platform version/fixture contract: {name} ({code}), iOS >=15.0; no Android release bump')
    return name, code


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--github-output', type=pathlib.Path)
    args = parser.parse_args()
    name, code = check()
    if args.github_output:
        with args.github_output.open('a', encoding='utf-8') as output:
            output.write(f'version={name}\nbuild={code}\n')
