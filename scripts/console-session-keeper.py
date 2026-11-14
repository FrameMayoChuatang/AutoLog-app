#!/usr/bin/env python3
"""개발용 Pleos 콘솔 세션 유지. 브라우저 없이 DDS 연결을 유지한다.

기어·속도·누적거리를 직접 발행하지 않는다. 벤더 내부 API이므로 이미지
업데이트 시 검증해야 하며, 배포용 앱에서 사용하지 않는다.
"""
import argparse
import fcntl
import os
from pathlib import Path
import signal
import tempfile
import threading
import urllib.parse
import urllib.request
import uuid


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def local_url(value):
    parsed = urllib.parse.urlsplit(value)
    if (parsed.scheme != 'http' or parsed.hostname not in ('127.0.0.1', 'localhost', '::1')
            or parsed.path not in ('', '/') or parsed.query or parsed.fragment
            or parsed.username is not None or parsed.password is not None):
        raise ValueError('로컬 adb forward 콘솔 HTTP 주소만 사용합니다')
    # 잘못된 포트는 네트워크 재시도 대신 실행 전에 거절한다.
    parsed.port
    return value.rstrip('/')


def heartbeat(base, client, closing=False):
    query = {'client': client}
    if closing:
        query['closing'] = '1'
    request = urllib.request.Request(base + '/browser/session?' + urllib.parse.urlencode(query), method='POST')
    # 로컬 세션 요청이 시스템 프록시나 리다이렉트로 외부에 전달되지 않게 한다.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    with opener.open(request, timeout=1) as response:
        if response.status != 200:
            raise OSError('콘솔 세션 요청 실패')


def keep_alive(base, client, stopped, stop_requested):
    connected = None
    try:
        while not stopped.is_set() and not stop_requested():
            try:
                heartbeat(base, client)
                if connected is not True:
                    print('콘솔 세션 연결됨', flush=True)
                connected = True
            except (OSError, ValueError):
                if connected is not False:
                    print('콘솔 연결 대기: 에뮬레이터와 adb forward를 확인하세요.', flush=True)
                connected = False
            stopped.wait(1)
    finally:
        # 다른 탭의 client를 닫지 않는다. 실패하면 콘솔의 세션 만료에 맡긴다.
        try:
            heartbeat(base, client, closing=True)
        except (OSError, ValueError):
            pass


def runtime_dir():
    path = Path(tempfile.gettempdir()) / f'autolog-console-session-{os.getuid()}'
    path.mkdir(mode=0o700, exist_ok=True)
    if path.is_symlink() or path.stat().st_uid != os.getuid():
        raise OSError('연결 유지 상태 디렉터리 소유자를 확인하세요.')
    path.chmod(0o700)
    return path


def control(command, base, directory):
    stop_file = directory / 'stop'
    # 잠금 파일은 삭제하지 않는다. 삭제 후 재생성하면 다른 inode를 잠글 수 있다.
    with (directory / 'session.lock').open('a+') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            lock.seek(0)
            client = lock.read().strip()
            if command == 'stop':
                if not client:
                    print('연결 유지 시작 중입니다. 잠시 후 다시 중지하세요.', flush=True)
                    return 1
                # PID에 signal을 보내지 않아 PID 재사용으로 다른 프로세스를 종료하지 않는다.
                stop_file.write_text(client)
                print('연결 유지 종료 요청 완료', flush=True)
            else:
                print('연결 유지 실행 중', flush=True)
            return 0 if command != 'run' else 1
        if command != 'run':
            print('연결 유지가 실행 중이 아닙니다.', flush=True)
            return 0
        client = 'autolog-keeper-' + uuid.uuid4().hex
        lock.seek(0)
        lock.truncate()
        lock.write(client)
        lock.flush()
        stopped = threading.Event()
        previous = {s: signal.signal(s, lambda *_: stopped.set()) for s in (signal.SIGINT, signal.SIGTERM)}

        def stop_requested():
            try:
                return stop_file.read_text().strip() == client
            except FileNotFoundError:
                return False

        print('개발용 콘솔 연결 유지 시작. 중지: 같은 스크립트의 stop 명령', flush=True)
        try:
            keep_alive(base, client, stopped, stop_requested)
        finally:
            for s, handler in previous.items():
                signal.signal(s, handler)
            print('콘솔 연결 유지 종료', flush=True)
        return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', nargs='?', choices=('run', 'status', 'stop'), default='run')
    parser.add_argument('--url', default='http://127.0.0.1:8088')
    args = parser.parse_args()
    try:
        base = local_url(args.url)
        return control(args.command, base, runtime_dir())
    except (OSError, ValueError) as error:
        parser.exit(1, f'실행 실패: {error}\n')


if __name__ == '__main__':
    raise SystemExit(main())
