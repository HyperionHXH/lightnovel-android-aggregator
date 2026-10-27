#!/usr/bin/env python3
"""Package only an unsigned arm64 iPhoneOS app, never a simulator .app."""
import argparse
import hashlib
import json
import pathlib
import plistlib
import shutil
import subprocess
import tempfile
import zipfile


def package(app: pathlib.Path, output: pathlib.Path):
    info = plistlib.loads((app / 'Info.plist').read_bytes())
    if info.get('CFBundleIdentifier') != 'io.github.hyperionhxh.mixn':
        raise ValueError('Unexpected iOS bundle identifier')
    binary = app / info['CFBundleExecutable']
    architectures = subprocess.check_output(['xcrun', 'lipo', '-archs', str(binary)], text=True).split()
    if architectures != ['arm64']:
        raise ValueError(f'Expected arm64 device executable, got {architectures}')
    platform = subprocess.check_output(['xcrun', 'vtool', '-show-build', str(binary)], text=True)
    if 'platform IOS\n' not in platform or 'IOSSIMULATOR' in platform:
        raise ValueError('Executable is not an iPhoneOS binary')
    if (app / 'embedded.mobileprovision').exists() or (app / '_CodeSignature').exists():
        raise ValueError('Unsigned deliverable unexpectedly contains a provisioning profile/signature')
    if not {'UIInterfaceOrientationPortrait', 'UIInterfaceOrientationLandscapeLeft'}.issubset(info.get('UISupportedInterfaceOrientations', [])):
        raise ValueError('Missing reader orientation support')
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        raise FileExistsError(f'Refusing to overwrite {output}')
    with tempfile.TemporaryDirectory(prefix='mixn-ipa-') as temp:
        payload = pathlib.Path(temp) / 'Payload'
        payload.mkdir()
        shutil.copytree(app, payload / app.name, symlinks=True)
        subprocess.run(['zip', '-q', '-r', '-y', str(output.resolve()), 'Payload'], cwd=temp, check=True)
    with zipfile.ZipFile(output) as archive:
        bad = archive.testzip()
        if bad:
            raise ValueError(f'Corrupt IPA member {bad}')
        if f'Payload/{app.name}/{binary.name}' not in archive.namelist():
            raise ValueError('Missing IPA executable')
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix(output.suffix + '.sha256').write_text(f'{digest}  {output.name}\n', encoding='utf-8')
    manifest = {'bundle': info['CFBundleIdentifier'], 'version': info['CFBundleShortVersionString'],
                'build': info['CFBundleVersion'], 'architectures': architectures, 'unsigned': True,
                'name': output.name, 'bytes': output.stat().st_size, 'sha256': digest}
    output.with_suffix('.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(manifest, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--app', type=pathlib.Path, required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    package(args.app, args.output)
