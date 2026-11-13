#!/usr/bin/env python3
"""로컬 AutoLog 서버에 시연용 회사를 만들고, 시연 중 필요한 관리자 작업을 한 줄로 실행한다.

  python3 tools/demo_server.py setup            # 회사·차량·승인 운전자·업무 목적 생성 (처음 한 번)
  python3 tools/demo_server.py pair             # 8자리 페어링 코드 발급 (10분, 1회용)
  python3 tools/demo_server.py drivers          # 운전자 목록과 승인 상태
  python3 tools/demo_server.py approve <이름>    # 승인 대기 운전자 승인
  python3 tools/demo_server.py trips            # 서버에 올라온 운행
  python3 tools/demo_server.py approve-trip <운행 id 앞자리>   # 관리자 승인 (이후 차량 수정은 거절됨)

서버 주소는 앱 빌드와 같은 AUTOLOG_SERVER_URL을 쓴다: 환경 변수 → 저장소 루트 .env(Git 제외) → 로컬 서버(http://127.0.0.1:8080).
계정 정보는 서버별로 tools/.demo-state*.json(로컬 전용, Git 제외)에 저장한다.
"""
import json
import os
import secrets
import sys
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

LOCAL = "http://127.0.0.1:8080"


def _server_url():
    if os.environ.get("AUTOLOG_SERVER_URL"):
        return os.environ["AUTOLOG_SERVER_URL"]
    env = Path(__file__).resolve().parent.parent / ".env"
    if env.is_file():
        for line in env.read_text().splitlines():
            key, _, value = line.partition("=")
            if key.strip() == "AUTOLOG_SERVER_URL" and value.strip():
                return value.strip().strip('"').strip("'")
    return LOCAL


BASE = _server_url().rstrip("/")
_HOST = BASE.split("://")[-1].replace(":", "_").replace("/", "_")
STATE = Path(__file__).with_name(".demo-state.json" if BASE == LOCAL else f".demo-state-{_HOST}.json")


def call(method, path, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urlopen(Request(BASE + path, data=data, headers=headers, method=method), timeout=15) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else None
    except HTTPError as e:
        return e.code, e.read().decode()
    except URLError as e:
        sys.exit(f"서버({BASE})에 연결할 수 없습니다: {e.reason}\n서버가 떠 있는지 확인하세요: curl {BASE}/actuator/health")


def admin(method, path, body=None):
    """관리자 토큰으로 호출하고, 토큰이 만료됐으면 다시 로그인해서 한 번 더 시도한다."""
    state = json.loads(STATE.read_text())
    code, res = call(method, path, body, state["token"])
    if code == 401:
        code, login = call("POST", "/api/auth/login", {"email": state["email"], "password": state["password"]})
        if code != 200:
            sys.exit(f"다시 로그인하지 못했습니다 ({code}). 서버 DB를 초기화했다면 setup을 다시 실행하세요.")
        state["token"] = login["accessToken"]
        STATE.write_text(json.dumps(state, ensure_ascii=False, indent=2))
        code, res = call(method, path, body, state["token"])
    if code >= 400:
        sys.exit(f"{method} {path}: HTTP {code} {res}")
    return res


def setup():
    if STATE.exists():
        sys.exit(f"이미 시연용 회사가 있습니다 ({STATE}). 새로 만들려면 이 파일을 지우고 다시 실행하세요.")
    suffix = secrets.token_hex(4)
    email, password = f"demo-{suffix}@example.test", "Demo-" + secrets.token_urlsafe(12)
    code, session = call("POST", "/api/auth/companies", {
        "companyName": "오토로그 시연 " + suffix, "ownerName": "시연 관리자", "email": email, "password": password,
    })
    if code >= 400:
        sys.exit(f"회사를 만들지 못했습니다: HTTP {code} {session}")
    STATE.write_text(json.dumps({"email": email, "password": password, "token": session["accessToken"]}, ensure_ascii=False, indent=2))
    vehicle = admin("POST", "/api/vehicles", {"registrationNumber": "12가3456", "model": "아이오닉 5", "active": True})
    state = json.loads(STATE.read_text())
    state["vehicle"] = vehicle["id"]
    STATE.write_text(json.dumps(state, ensure_ascii=False, indent=2))
    admin("POST", "/api/drivers", {"name": "김운전", "department": "영업팀"})
    for name in ("고객 미팅", "물품 수령"):
        admin("POST", "/api/catalog/purposes", {"name": name, "active": True})
    print("시연용 회사를 만들었습니다: 차량 12가3456, 승인된 운전자 김운전(영업팀), 업무 목적 2개")
    print(f"서버 {BASE} · 관리자 로그인 정보는 {STATE} 에 있습니다. 다음: python3 tools/demo_server.py pair")


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    arg = sys.argv[2] if len(sys.argv) > 2 else None
    if cmd == "setup":
        return setup()
    if not STATE.exists():
        sys.exit("먼저 setup을 실행하세요: python3 tools/demo_server.py setup")
    if cmd == "pair":
        vehicle = json.loads(STATE.read_text())["vehicle"]
        print(admin("POST", f"/api/vehicles/{vehicle}/pairing")["token"])
    elif cmd == "drivers":
        for d in admin("GET", "/api/drivers"):
            print(f'{d["status"]:8} {d["name"]} / {d["department"]}')
    elif cmd == "approve" and arg:
        matches = [d for d in admin("GET", "/api/drivers") if d["name"] == arg and d["status"] == "PENDING"]
        if not matches:
            sys.exit(f"승인 대기 중인 '{arg}' 운전자가 없습니다. python3 tools/demo_server.py drivers 로 확인하세요.")
        for d in matches:
            admin("POST", f'/api/drivers/{d["id"]}/approve')
            print(f'승인했습니다: {d["name"]} / {d["department"]}')
    elif cmd == "trips":
        for t in admin("GET", "/api/trips")["items"]:
            km = f'{t["startOdometerKm"]} → {t["endOdometerKm"]}'
            print(f'{t["id"][:8]}  {t["state"]:11} {t["classification"]:11} {t["approvalStatus"]:10} {t.get("driverName") or "-":8} {km}')
    elif cmd == "approve-trip" and arg:
        matches = [t for t in admin("GET", "/api/trips")["items"] if t["id"].startswith(arg)]
        if len(matches) != 1:
            sys.exit(f"'{arg}'로 시작하는 운행이 {len(matches)}건입니다. trips 로 id를 확인하세요.")
        t = matches[0]
        admin("POST", f'/api/trips/{t["id"]}/approve', {"expectedVersion": t["serverVersion"]})
        print(f'승인했습니다: {t["id"][:8]}')
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
