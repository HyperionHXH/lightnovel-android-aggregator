#!/usr/bin/env python3
"""Apply WebKit bug 293831's simulator-only runtime path to native test processes."""
import argparse
import json
import pathlib
import plistlib
import subprocess


def configure(derived, device):
    devices = json.loads(subprocess.check_output(['xcrun', 'simctl', 'list', 'devices', 'available', '--json']))['devices']
    runtime_id = next(key for key, entries in devices.items() if any(item['udid'] == device for item in entries))
    runtimes = json.loads(subprocess.check_output(['xcrun', 'simctl', 'list', 'runtimes', '--json']))['runtimes']
    runtime = next(item for item in runtimes if item['identifier'] == runtime_id)
    path = pathlib.Path(runtime['bundlePath']) / 'Contents/Resources/RuntimeRoot/System/Cryptexes/OS/usr/lib/swift'
    if not (path / 'libswiftWebKit.dylib').is_file():
        raise RuntimeError('Pinned simulator WebKit runtime library missing: ' + str(path))
    products = derived / 'Build/Products'
    files = list(products.glob('*.xctestrun'))
    if len(files) != 1:
        raise RuntimeError('Expected exactly one built native test configuration')
    file = files[0]
    data = plistlib.loads(file.read_bytes())
    targets = [target for configuration in data.get('TestConfigurations', []) for target in configuration.get('TestTargets', [])]
    if not targets:
        targets = [target for key, target in data.items() if not key.startswith('__') and isinstance(target, dict)]
    if not targets:
        raise RuntimeError('No native test targets in built xctestrun')
    for target in targets:
        for key in ['EnvironmentVariables', 'TestingEnvironmentVariables']:
            target.setdefault(key, {})['DYLD_FALLBACK_LIBRARY_PATH'] = str(path)
    file.write_bytes(plistlib.dumps(data))
    print('Simulator-only WebKit 293831 environment:', path, 'targets:', len(targets))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--derived', type=pathlib.Path, default=pathlib.Path('build/ios/simulator'))
    parser.add_argument('--device', required=True)
    args = parser.parse_args()
    configure(args.derived, args.device)
