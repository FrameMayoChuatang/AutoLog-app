#!/usr/bin/env python3
"""개발용 모의 거리 입력 생성기. 실측 거리나 실차 배포 기능이 아니다.

Virtual Console의 가속/브레이크는 속도만 변경하므로, 관측한 속도를 적산해
같은 콘솔의 누적거리 입력으로 발행한다. 앱은 콘솔의 누적거리만 수신한다.
Ctrl+C로 종료하며 속도·기어·기존 운행 기록은 변경하지 않는다.
"""
import argparse
import fcntl
import json
import math
import tempfile
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Sample:
    km: float
    speed: float
    gear: str
    at: float


def sample(payload, now):
    odo, controls = payload['odometer'], payload['controls']
    if not all(v is True for v in (odo['bridge_ready'], odo['emulator_active'],
                                   controls['emulator_active'], controls['dds_ready']['speed'])):
        raise ValueError('콘솔 거리/속도 연결 준비 안 됨')
    km, speed = odo['traveled_distance_km'], controls['speed_kph']
    if any(isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v)
           for v in (km, speed)) or not 0 <= km <= 1_000_000 or not 0 <= speed <= 240:
        raise ValueError('콘솔 거리/속도 값 오류')
    gear = payload['gear']['actual']['value']
    if payload['gear']['bridge_ready'] is not True or gear not in ('Park', 'Drive', 'Reverse', 'Neutral'):
        raise ValueError('기어 수신 오류')
    return Sample(km, speed, gear, now)


def next_km(previous, current, last_written):
    if previous is None:
        return None
    dt = current.at - previous.at
    # 관측 누락 구간 및 수동 거리 변경은 추측으로 메우지 않는다.
    if not 0 < dt <= 3 or abs(current.km - last_written) > 0.00001:
        return None
    if previous.gear == 'Park' or current.gear == 'Park':
        return None
    increment = (previous.speed + current.speed) / 2 * dt / 3600
    target = current.km + increment
    return target if increment > 0 and target <= 1_000_000 else None


def request(base, path, method='GET'):
    with urllib.request.urlopen(urllib.request.Request(base + path, method=method), timeout=1) as response:
        if response.status != 200:
            raise ValueError(f'콘솔 HTTP {response.status}')
        return json.load(response)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://127.0.0.1:8088')
    args = parser.parse_args()
    parsed = urllib.parse.urlsplit(args.url)
    if (parsed.scheme != 'http' or parsed.hostname not in ('127.0.0.1', 'localhost', '::1')
            or parsed.path not in ('', '/') or parsed.query or parsed.fragment or parsed.username):
        parser.error('로컬 adb forward 콘솔 HTTP 주소만 사용합니다')
    base = args.url.rstrip('/')
    # 동일 호스트 사용자에서 콘솔 주소가 달라도 중복 적산을 막는다.
    with (Path(tempfile.gettempdir()) / 'autolog-console-driving.lock').open('w') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            parser.exit(1, '이미 개발용 주행 시뮬레이터가 실행 중입니다.\n')
        previous, last_written = None, 0.0
        print('개발용 모의 거리 자동 발행 시작. 콘솔에서 D/R + 가속을 조작하세요. Ctrl+C: 종료', flush=True)
        try:
            while True:
                try:
                    current = sample(request(base, '/vehicle/state'), time.monotonic())
                    target = next_km(previous, current, last_written)
                    if target is not None:
                        # 계산 중 수동 거리 변경이 관측되면 해당 입력을 덮어쓰지 않는다.
                        latest = sample(request(base, '/vehicle/state'), time.monotonic())
                        if abs(latest.km - current.km) > 0.00001 or latest.gear == 'Park':
                            current, target = latest, None
                        else:
                            request(base, '/vehicle/odometer?traveled_distance_km=' + str(target), 'PUT')
                    previous = current
                    last_written = target if target is not None else current.km
                except (OSError, ValueError, KeyError, TypeError) as error:
                    previous = None
                    print(f'적산 중단, 재연결 대기: {error}', flush=True)
                time.sleep(0.5)
        except KeyboardInterrupt:
            print('\n모의 거리 발행 종료', flush=True)


if __name__ == '__main__':
    main()
