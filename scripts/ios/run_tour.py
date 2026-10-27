#!/usr/bin/env python3
"""Run native iPhone UI acceptance, capture exact-UDID screenshots and recording."""
import argparse
import hashlib
import json
import pathlib
import re
import signal
import shutil
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

TOUR = [
    ('01_onboarding', '首次使用与下载目录'), ('02_discover', '发现与作品作者'),
    ('03_source', '轻书架独立来源'), ('03a_mandatory_woff2', '真实 WOFF2 专用字体原生渲染'),
    ('04_search', '聚合搜索结果'),
    ('05_detail', '作品详情与标签'), ('06_catalog', '分卷与章节目录'),
    ('07_comments', '评论与回复'), ('08_reader_paged', '左右分页正文'),
    ('08a_reader_image', '原生插图缩放预览'), ('08b_reader_image_actions', '长按并保存到系统相册'),
    ('09_reader_menu', '阅读菜单'), ('10_reader_settings', '阅读参数'),
    ('11_reader_scroll', '上下滚动正文'), ('12_reader_card', '卡片阅读正文'),
    ('13_shelf', '统一书架'), ('14_downloads', '离线下载管理'),
    ('14a_epub_export', '完整离线 EPUB 原生分享'), ('14b_txt_export', 'UTF-8 TXT 原生分享'),
    ('15_history', '阅读记录'), ('16_profile', '我的与来源账号'),
    ('17_accounts', '独立来源登录'), ('17a_signed_in', '独立来源登录与等级经验'),
    ('18_settings', '应用与阅读设置'),
    ('19_fonts', '字体选择'), ('20_storage', '缓存占用与安全清理'),
    ('21_about', '关于及未签名说明'), ('22_error', '来源错误与重试'),
    ('23_dark', '深色模式'), ('24_large_text', '大字可访问性'),
]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(args):
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    screenshots = output / 'screenshots'
    subprocess.run(['xcrun', 'simctl', 'privacy', args.device, 'grant', 'photos-add',
                    'io.github.hyperionhxh.mixn'], check=True, capture_output=True, timeout=30)
    screenshots.mkdir()
    captures = []
    exported = []
    failures = []
    expected = dict(TOUR)
    lock = threading.Lock()

    class Capture(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            request = urlsplit(self.path)
            if request.path == '/failure':
                try:
                    subprocess.run(['xcrun', 'simctl', 'io', args.device, 'screenshot', str(output / 'failure-surface.png')],
                                   check=True, capture_output=True, timeout=30)
                    self.send_response(200); self.send_header('Content-Length', '0'); self.end_headers()
                except Exception as error:
                    failures.append('Failure capture: ' + str(error)); self.send_error(500, str(error))
                return
            name = parse_qs(request.query).get('name', [''])[0]
            with lock:
                if request.path != '/capture' or name not in expected or any(c['name'] == name for c in captures):
                    self.send_error(400, 'Unknown or duplicate capture'); return
                try:
                    path = screenshots / (name + '.png')
                    subprocess.run(['xcrun', 'simctl', 'io', args.device, 'screenshot', str(path)],
                                   check=True, capture_output=True, timeout=30)
                    from PIL import Image
                    with Image.open(path) as image:
                        image.verify()
                    with Image.open(path) as image:
                        width, height = image.size
                    captures.append({'order': len(captures) + 1, 'name': name, 'route': expected[name],
                                     'file': 'screenshots/' + path.name, 'bytes': path.stat().st_size,
                                     'width': width, 'height': height, 'sha256': digest(path)})
                    if name in ['14a_epub_export', '14b_txt_export']:
                        suffix = '.epub' if name == '14a_epub_export' else '.txt'
                        container = pathlib.Path(subprocess.check_output(
                            ['xcrun', 'simctl', 'get_app_container', args.device, 'io.github.hyperionhxh.mixn', 'data'],
                            text=True, timeout=15).strip())
                        candidates = list((container / 'tmp').glob('mixn-ui-*/Library/Exports/*' + suffix))
                        if len(candidates) != 1:
                            raise RuntimeError('Expected one actual isolated app export: ' + suffix)
                        export_dir = output / 'exports'
                        export_dir.mkdir(exist_ok=True)
                        destination = export_dir / ('native-export' + suffix)
                        shutil.copyfile(candidates[0], destination)
                        exported.append({'file': 'exports/' + destination.name, 'bytes': destination.stat().st_size,
                                         'sha256': digest(destination)})
                    self.send_response(200); self.send_header('Content-Length', '2'); self.end_headers(); self.wfile.write(b'OK')
                except Exception as error:
                    failures.append(str(error)); self.send_error(500, str(error))

    server = ThreadingHTTPServer(('127.0.0.1', 18764), Capture)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    env_file = output / 'capture-port.txt'
    env_file.write_text(str(server.server_port))
    recording = output / 'ios-tour.mp4'
    video_log = (output / 'recording.log').open('wb')
    video = subprocess.Popen(['xcrun', 'simctl', 'io', args.device, 'recordVideo', '--codec=h264', str(recording)], stdout=video_log, stderr=subprocess.STDOUT)
    result = None
    started = time.monotonic()
    try:
        test_runs = list((args.derived / 'Build/Products').glob('*.xctestrun'))
        if len(test_runs) != 1:
            raise RuntimeError('Expected exactly one configured native xctestrun')
        command = ['xcodebuild', 'test-without-building', '-xctestrun', str(test_runs[0]),
                   '-destination', 'platform=iOS Simulator,id=' + args.device,
                   '-parallel-testing-enabled', 'NO', '-only-testing:MixnUITests',
                   '-resultBundlePath', str(output / 'UITour.xcresult')]
        with (output / 'xcode-ui.log').open('wb') as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=1200).returncode
    finally:
        if video.poll() is None:
            video.send_signal(signal.SIGINT)
        try:
            video.wait(timeout=30)
        except subprocess.TimeoutExpired:
            video.kill(); video.wait(); failures.append('recordVideo did not finalize normally')
        video_log.close()
        server.shutdown(); server.server_close(); thread.join(timeout=5)
        manifest = {'schema': 1, 'device': args.device, 'captures': captures, 'errors': failures,
                    'expected': [n for n, _ in TOUR], 'xcodeExit': result,
                    'elapsedSeconds': round(time.monotonic() - started, 3)}
        manifest['exports'] = exported
        if recording.exists():
            manifest['video'] = {'file': recording.name, 'bytes': recording.stat().st_size, 'sha256': digest(recording)}
        (output / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    if result != 0 or failures or [x['name'] for x in captures] != list(expected):
        print((output / 'xcode-ui.log').read_text(encoding='utf-8', errors='replace'))
        raise RuntimeError('Native UI acceptance/capture incomplete: ' + repr(manifest))
    subprocess.run(['python3', 'scripts/ios/verify_artifacts.py', '--tour', str(output), '--ffprobe', 'ffprobe'], check=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--device', required=True)
    parser.add_argument('--derived', type=pathlib.Path, default=pathlib.Path('build/ios/simulator'))
    parser.add_argument('--output', type=pathlib.Path, default=pathlib.Path('build/ios/visual-tour'))
    run(parser.parse_args())
