# 2차 PT 시연 환경 준비

발표 노트북 한 대에서 서버와 Pleos Connect 에뮬레이터를 함께 띄워 시연한다. 실차와 서버 호스팅은 필요 없다(운영사무국 답변, 2026-11-11).

이 문서는 이슈 #36 중 **환경 구동과 시연 데이터**를 다룬다. 화면 동선과 확인 항목은 [B_DEMO_SCENARIO.md](B_DEMO_SCENARIO.md)를 따른다.

## 한눈에 보기

| 순서 | 할 일 | 확인 |
|---|---|---|
| 1 | Docker로 DB 실행 | `autolog-db-1`이 healthy |
| 2 | JDK 21로 서버 실행 | `curl http://127.0.0.1:8080/actuator/health` → `UP` |
| 3 | 시연용 회사 만들기 (처음 한 번) | `python3 tools/demo_server.py drivers`에 김운전 표시 |
| 4 | 에뮬레이터 실행, Wi‑Fi 확인 | 상단 신호 아이콘에 X 없음 |
| 5 | APK 빌드·설치 | 앱이 운전자 선택 화면으로 열림 |
| 6 | 누적거리 넣기 | 계기판이 0 km가 아님 |
| 7 | 페어링 코드 발급 → 앱에 입력 | `차량 연결됨 · 12가3456` |

1~3은 발표 전날, 4~7은 발표 직전에 한다. 에뮬레이터를 다시 켜면 4와 6은 다시 해야 한다.

## 1. DB와 서버

서버 저장소(`AutoLog-server`)에서 실행한다.

```sh
docker compose up -d --wait
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw spring-boot:run -Dspring-boot.run.profiles=emulator
```

- Docker Desktop이 켜져 있어야 한다. 꺼져 있으면 `failed to connect to the docker API`가 나온다.
- 서버는 JDK 21만 받는다. 셸의 기본 JDK가 17이면 `RequireJavaVersion failed`로 바로 끝나므로 위처럼 `JAVA_HOME`을 지정한다. 앱 빌드는 JDK 17을 그대로 쓴다.
- 확인: `curl http://127.0.0.1:8080/actuator/health` → `{"status":"UP"}`

## 2. 시연용 회사와 데이터

앱 저장소에서 실행한다. 로컬 서버에 회사·차량·승인된 운전자·업무 목적을 한 번에 만든다.

```sh
python3 tools/demo_server.py setup
```

| 만들어지는 것 | 값 |
|---|---|
| 차량 | 12가3456 · 아이오닉 5 |
| 승인된 운전자 | 김운전 / 영업팀 |
| 업무 목적 | 고객 미팅, 물품 수령 |

- 관리자 계정은 `tools/.demo-state.json`에 저장된다. Git에 올라가지 않으며, 화면 공유나 녹화에 비추지 않는다.
- **승인 대기 운전자**는 서버에서 미리 만들지 않는다. 시연 중 앱에서 `운전자 추가`로 프로필을 만들면 서버에 승인 대기로 등록된다.
- 승인된 운전자 김운전은 차량을 연결하면 앱의 프로필 목록에 자동으로 나타난다.
- 서버 DB를 초기화했다면 `tools/.demo-state.json`을 지우고 `setup`을 다시 실행한다.

시연 중 쓰는 명령:

| 명령 | 용도 |
|---|---|
| `python3 tools/demo_server.py pair` | 8자리 페어링 코드 발급 |
| `python3 tools/demo_server.py drivers` | 운전자와 승인 상태 |
| `python3 tools/demo_server.py approve <이름>` | 승인 대기 운전자 승인 |
| `python3 tools/demo_server.py trips` | 서버에 올라온 운행 (분류·승인 상태·누적거리) |
| `python3 tools/demo_server.py approve-trip <id 앞자리>` | 운행을 관리자 승인. 이후 차량에서 수정하면 `전송 실패` |

관리자 토큰이 만료되면 스크립트가 알아서 다시 로그인한다.

## 3. 에뮬레이터

Pleos Connect 2.1.0(API 34) AVD를 실행한다.

**Wi‑Fi 확인.** 상단 신호 아이콘에 X가 있으면 앱이 서버에 닿지 못해 페어링에서 "서버에 연결할 수 없습니다"가 나온다. 에뮬레이터 화면에서 설정 → 연결 → Wi‑Fi → `AndroidWifi`를 눌러 연결한다. Android Studio의 Extended Controls(Cellular)에서는 고칠 수 없다.

터미널로 확인하려면:

```sh
adb shell ping -c1 10.0.2.2
```

인터넷은 없어도 된다. 앱은 인터넷 확인 여부와 관계없이 로컬 서버로 동기화한다(#82).

## 4. APK 빌드와 설치

앱 저장소에서 실행한다.

```sh
./gradlew :app:assembleDebug
adb install -r --user $(adb shell am get-current-user) app/build/outputs/apk/debug/app-debug.apk
adb shell am start --user $(adb shell am get-current-user) -n com.framemayo.autolog/.ui.MainActivity
```

- 서버 주소 기본값은 `http://10.0.2.2:8080`이다(에뮬레이터에서 본 노트북). 포트를 바꿨거나 다른 PC의 서버를 쓸 때만 `-PserverUrl=http://<주소>:<포트>`를 붙인다.
- debug 빌드만 http 서버에 연결할 수 있다. release 빌드는 시연에 쓰지 않는다.
- Pleos 에뮬레이터는 앱을 사용자 10에서 실행한다. `--user` 없이 설치하면 화면에 앱이 보이지 않을 수 있다.
- **운행 중에는 설치하지 않는다.** 설치하면 앱이 재시작되고 진행 중이던 운행이 `중단됨`으로 남는다.
- 기존 기록을 지우지 않으려면 앱 삭제나 데이터 초기화 없이 `-r`로 덮어쓴다.

## 5. 누적거리와 기어 (Virtual Console)

에뮬레이터는 켤 때마다 누적거리가 0 km다. 앱은 누적거리가 0보다 클 때만 운행을 시작하므로, 넣지 않으면 "주행거리 수신 대기" 안내창이 뜬다.

```sh
adb forward tcp:8088 tcp:8088
curl -X PUT "http://127.0.0.1:8088/vehicle/gear?gear=Park"
curl -X PUT "http://127.0.0.1:8088/vehicle/odometer?traveled_distance_km=1000"
```

브라우저에서 `http://127.0.0.1:8088/`을 열어 같은 값을 화면으로 바꿀 수도 있다.

- **주행해도 누적거리는 자동으로 늘지 않는다.** 콘솔은 속도와 누적거리를 따로 다룬다. 운행 거리를 보여 주려면 운행 중에 누적거리 값을 직접 올린다. 예: 1000에서 1012로 바꾸면 12 km 운행으로 기록된다.
- 누적거리는 현재 값보다 크게만 바꾼다.
- 운전자 선택, 운행 종료, 운행 확인은 P 기어에서만 된다.

## 6. 차량 연결

페어링 코드는 10분 동안 한 번만 쓸 수 있으므로 **입력 직전에** 발급한다.

```sh
python3 tools/demo_server.py pair
```

앱에서 프로필(또는 `운전자는 나중에 선택`)을 누르고, 안내창의 `차량 연결하기` → 8자리 코드 입력 → `연결`. 앞자리 0도 그대로 입력한다. `차량 연결됨 · 12가3456`이 보이면 `운전자 선택으로 돌아가기`를 누른다.

## 7. 예외 상황 만들기

[B_DEMO_SCENARIO.md](B_DEMO_SCENARIO.md)의 예외 시연에 필요한 조작이다.

| 상황 | 만드는 방법 | 되돌리기 |
|---|---|---|
| 승인 대기 | 앱에서 새 프로필을 만들어 운행하고 목적 저장 → `전송 보류` | `demo_server.py approve <이름>`. 30초~5분 안에 자동 전송 |
| 오프라인 | 서버를 띄운 터미널에서 Ctrl+C 후 운행 기록 → `전송 대기` | 서버를 다시 실행. 30초~5분 안에 자동 재전송, 중복 없음 |
| 서버 거절 | `demo_server.py approve-trip <id>` 후 앱에서 `확인 및 수정` → `전송 실패` | 되돌릴 수 없음. 시연용 운행으로만 한다 |
| 차량 미연결 | 기록 화면의 `연결 해제` | `demo_server.py pair`로 새 코드 발급 후 다시 연결 |
| 중단된 운행 | 운행 중에 `adb shell am force-stop --user 10 com.framemayo.autolog` 후 앱 재실행 | 되돌릴 수 없음(#40) |

- 재전송을 기다리기 어렵다면 앱에서 아무 운행의 목적을 저장하면 동기화가 바로 다시 돈다.
- 승인 대기 운전자의 운행이 여러 건 있어도 다른 운전자의 운행은 정상 전송된다(#86).

## 문제 해결

| 증상 | 원인 | 조치 |
|---|---|---|
| "차량 연결이 필요합니다" | 차량이 서버에 연결되지 않음 | 6번대로 페어링 |
| "주행거리 수신 대기" | 누적거리가 0 km | 5번대로 누적거리 입력 |
| "서버에 연결할 수 없습니다" | 서버가 꺼졌거나 에뮬레이터 Wi‑Fi가 끊김 | 1번 health 확인, 3번 Wi‑Fi 확인 |
| "페어링 코드가 올바르지 않거나 만료" | 코드가 10분 지났거나 이미 사용됨 | `pair`로 새 코드 발급 |
| "시도 횟수가 너무 많습니다" | 10분에 20회 제한 | 안내된 시간만큼 기다림 |
| 화면이 잠기고 운전자 선택이 안 됨 | 기어가 P가 아님, 또는 앱 실행 중 바꾼 기어가 반영 안 됨(#13) | 콘솔에서 P로 변경. 안 되면 앱을 다시 실행 |
| 운행 거리가 0 km | 누적거리를 올리지 않음 | 운행 중 누적거리 값을 올림 |
| 운행이 `전송 대기`에서 안 바뀜 | 서버에 닿지 못함 | health와 Wi‑Fi 확인. 재시도는 최대 5분 간격 |
| 서버가 바로 종료됨 | JDK 17로 실행 | `JAVA_HOME`을 JDK 21로 지정 |
| Android Studio의 Run 버튼이 회색 | 인덱싱 또는 Gradle 동기화 중 | 끝날 때까지 기다리거나 4번의 터미널 명령 사용 |

## 발표 직전 점검

- [ ] Docker와 서버가 떠 있고 health가 `UP`
- [ ] 에뮬레이터 Wi‑Fi 연결, `adb shell ping -c1 10.0.2.2` 성공
- [ ] 최신 main으로 빌드한 debug APK 설치
- [ ] 누적거리가 0보다 크고 기어가 P
- [ ] 페어링 완료, 김운전 프로필 표시
- [ ] 운행 1건을 끝까지 돌려 `demo_server.py trips`에서 서버 반영 확인
- [ ] 화면에 `tools/.demo-state.json`, 토큰, 터미널 기록이 보이지 않음

## 에뮬레이터로 보여 줄 수 없는 것

기획서의 향후 계획으로 다룬다. 앱은 이 중 어느 것에도 의존하지 않는다.

- Trip 거리·시간 API (에뮬레이터 미지원)
- 주행에 따른 누적거리 자동 증가, 시동·PowerState 연계 자동 시작·종료
- 헤드업 알림, VIN 자동 식별
- 실차 GNSS 정확도, CarUxRestrictions 연동
