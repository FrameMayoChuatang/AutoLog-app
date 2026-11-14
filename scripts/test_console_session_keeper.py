import contextlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.parse


SCRIPT = Path(__file__).with_name('console-session-keeper.py')
spec = importlib.util.spec_from_file_location('keeper', SCRIPT)
keeper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(keeper)


class KeeperTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.calls = []
        self.changed = threading.Condition()
        self.http_status = 200
        self.processes = []
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                with owner.changed:
                    owner.calls.append((self.command, self.path, owner.http_status))
                    owner.changed.notify_all()
                self.send_response(owner.http_status)
                if owner.http_status == 302:
                    self.send_header('Location', '/unexpected-redirect')
                self.end_headers()

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.server.daemon_threads = True
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.worker.start()
        self.url = f'http://127.0.0.1:{self.server.server_port}'

    def tearDown(self):
        for process in self.processes:
            if process.poll() is None:
                process.terminate()
            try:
                process.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.communicate()
        self.server.shutdown()
        self.server.server_close()
        self.worker.join(timeout=5)
        self.directory.cleanup()

    def start(self):
        code = (
            'import importlib.util,sys; from pathlib import Path; '
            's=importlib.util.spec_from_file_location("keeper",sys.argv[1]); '
            'm=importlib.util.module_from_spec(s); s.loader.exec_module(m); '
            'sys.exit(m.control("run",sys.argv[2],Path(sys.argv[3])))'
        )
        process = subprocess.Popen([sys.executable, '-B', '-c', code, str(SCRIPT), self.url, self.directory.name],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.processes.append(process)
        return process

    def wait_for(self, predicate):
        deadline = time.monotonic() + 6
        with self.changed:
            while not predicate():
                remaining = deadline - time.monotonic()
                self.assertGreater(remaining, 0, '세션 요청 대기 시간 초과')
                self.changed.wait(remaining)

    def command(self, command):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = keeper.control(command, self.url, Path(self.directory.name))
        return code, output.getvalue()

    def test_heartbeat_repeats_and_stop_closes_only_its_client(self):
        process = self.start()
        self.wait_for(lambda: len(self.calls) >= 2)
        self.assertEqual(0, self.command('stop')[0])
        self.assertEqual(0, process.wait(timeout=5))
        queries = [urllib.parse.parse_qs(urllib.parse.urlsplit(path).query) for _, path, _ in self.calls]
        self.assertEqual(1, len({q['client'][0] for q in queries}))
        self.assertNotIn('closing', queries[0])
        self.assertEqual(['1'], queries[-1]['closing'])
        self.assertTrue(all(method == 'POST' and path.startswith('/browser/session?')
                            for method, path, _ in self.calls))
        self.assertIn('실행 중이 아닙니다', self.command('status')[1])

    def test_duplicate_process_does_not_send_or_close_a_session(self):
        first = self.start()
        self.wait_for(lambda: len(self.calls) >= 1)
        self.assertIn('실행 중', self.command('status')[1])
        second = self.start()
        self.assertEqual(1, second.wait(timeout=5))
        self.assertIsNone(first.poll())
        self.assertTrue(all('closing=' not in path for _, path, _ in self.calls))

    def test_failure_retries_and_recovers_with_the_same_client(self):
        self.http_status = 503
        process = self.start()
        self.wait_for(lambda: any(status == 503 for _, _, status in self.calls))
        self.http_status = 200
        self.wait_for(lambda: any(status == 200 for _, _, status in self.calls))
        process.terminate()
        output, error = process.communicate(timeout=5)
        self.assertEqual(0, process.returncode, error)
        self.assertIn('연결 대기', output)
        self.assertIn('연결됨', output)
        clients = {urllib.parse.parse_qs(urllib.parse.urlsplit(p).query)['client'][0] for _, p, _ in self.calls}
        self.assertEqual(1, len(clients))
        self.assertIn('closing=1', self.calls[-1][1])

    def test_previous_stop_request_does_not_stop_next_instance(self):
        first = self.start()
        self.wait_for(lambda: len(self.calls) >= 1)
        first_client = self.calls[0][1]
        self.command('stop')
        self.assertEqual(0, first.wait(timeout=5))
        previous_count = len(self.calls)
        second = self.start()
        self.wait_for(lambda: len(self.calls) >= previous_count + 2)
        self.assertIsNone(second.poll())
        self.assertNotEqual(first_client, self.calls[-1][1])

    def test_delayed_stop_cannot_target_a_replacement_process(self):
        first = self.start()
        self.wait_for(lambda: len(self.calls) >= 1)
        first_query = urllib.parse.parse_qs(urllib.parse.urlsplit(self.calls[0][1]).query)
        code = '''
import importlib.util, sys, time
from pathlib import Path
s = importlib.util.spec_from_file_location('keeper', sys.argv[1])
m = importlib.util.module_from_spec(s)
s.loader.exec_module(m)
directory = Path(sys.argv[3])
original = m.fcntl.flock
def delayed_failure(fd, operation):
    try:
        return original(fd, operation)
    except BlockingIOError:
        (directory / 'stop-paused').touch()
        deadline = time.monotonic() + 10
        while not (directory / 'resume-stop').exists():
            if time.monotonic() > deadline:
                raise RuntimeError('stop 재개 신호 없음')
            time.sleep(0.01)
        raise
m.fcntl.flock = delayed_failure
sys.exit(m.control('stop', sys.argv[2], directory))
'''
        stop = subprocess.Popen([sys.executable, '-B', '-c', code, str(SCRIPT), self.url, self.directory.name],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.processes.append(stop)
        paused = Path(self.directory.name) / 'stop-paused'
        deadline = time.monotonic() + 5
        while not paused.exists():
            self.assertLess(time.monotonic(), deadline, 'stop 잠금 실패 지점 대기 시간 초과')
            time.sleep(0.01)
        first.terminate()
        self.assertEqual(0, first.wait(timeout=5))
        second = self.start()
        # stop이 client ID를 읽기 직전에 멈춘 동안 대체 프로세스 시작을 시도한다.
        # 제어 잠금이 없다면 이 구간에서 새 ID가 게시되어 이전 stop의 대상이 바뀐다.
        time.sleep(0.3)
        self.assertEqual(first_query['client'][0],
                         (Path(self.directory.name) / 'session.lock').read_text().strip())
        (Path(self.directory.name) / 'resume-stop').touch()
        self.assertEqual(0, stop.wait(timeout=5))
        self.assertEqual(first_query['client'][0], (Path(self.directory.name) / 'stop').read_text())
        self.wait_for(lambda: len({urllib.parse.parse_qs(urllib.parse.urlsplit(p).query)['client'][0]
                                   for _, p, _ in self.calls}) == 2)
        self.assertIsNone(second.poll())
        self.command('stop')
        self.assertEqual(0, second.wait(timeout=5))

    def test_crashed_process_releases_lock_without_signalling_other_processes(self):
        first = self.start()
        self.wait_for(lambda: len(self.calls) >= 1)
        first.kill()
        first.wait(timeout=5)
        previous_count = len(self.calls)
        second = self.start()
        self.wait_for(lambda: len(self.calls) > previous_count)
        self.assertIsNone(second.poll())
        self.command('stop')
        self.assertEqual(0, second.wait(timeout=5))

    def test_stop_without_running_process_does_not_leave_a_request(self):
        self.assertEqual(0, self.command('stop')[0])
        self.assertFalse((Path(self.directory.name) / 'stop').exists())

    def test_redirect_is_rejected_without_following_another_path(self):
        self.http_status = 302
        with self.assertRaises(urllib.error.HTTPError):
            keeper.heartbeat(self.url, 'test-client')
        self.assertEqual([('POST', '/browser/session?client=test-client', 302)], self.calls)

    def test_only_loopback_http_without_credentials_or_paths_is_allowed(self):
        for value in ('https://127.0.0.1', 'http://example.test', 'http://127.0.0.1.evil.test',
                      'http://user@localhost', 'http://localhost/path', 'http://localhost?x=1',
                      'http://localhost#x', 'http://localhost:invalid'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                keeper.local_url(value)
        for value in ('http://127.0.0.1:8088', 'http://localhost:8088', 'http://[::1]:8088'):
            self.assertEqual(value, keeper.local_url(value + '/'))


if __name__ == '__main__':
    unittest.main()
