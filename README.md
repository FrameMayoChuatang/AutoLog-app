# 오토로그 (AutoLog)

법인 승용차의 운행기록을 차량에서 수집하고, 운전자의 목적 확인과 관리자의 월말 취합을 줄여주는 서비스.

## APK

[공모전 제출용 APK 다운로드](apk/프레임마요추어탕_오토로그_release.apk)

## 테스트 환경 및 설정

| 항목 | 내용 |
| --- | --- |
| 개발·테스트 에뮬레이터 | Pleos Connect **2.1.0**, Android API **34** |
| 화면 규격 | **2560 × 1440**, **160 dpi**, 가로 화면 |
| 네트워크 | 에뮬레이터에서 운영 서버에 접근 가능해야 함 |
| 차량 연결 | 심사용 영구 페어링 코드: **`71242776`** |
| 차량 신호 | Virtual Console 연결 유지, **P 기어**, **0보다 큰 누적거리** 필요 |

제출 APK에는 서버 주소가 포함되어 있습니다. 현재 에뮬레이터의 SDK 누적거리 전달 문제로 Virtual Console 누적거리를 사용합니다.

## 실행 방법

### 1. APK 설치 및 실행

Pleos Connect AVD를 실행하고 네트워크 연결을 확인한 뒤, 저장소 루트에서 실행합니다.

```sh
adb install -r --user $(adb shell am get-current-user) "apk/프레임마요추어탕_오토로그_release.apk"
adb shell am start --user $(adb shell am get-current-user) -n com.framemayo.autolog/.ui.MainActivity
```

기존 설치본과 서명이 달라 업데이트가 거절되면 새 AVD에서 설치합니다. 기존 운행기록을 보존하려면 앱 삭제나 데이터 초기화를 하지 않습니다.

### 2. 차량 신호 준비

```sh
adb forward tcp:8088 tcp:8088
```

브라우저에서 `http://127.0.0.1:8088/`을 **열어 둔 상태로**, 별도 터미널에서 초기값을 설정합니다. 기존 기록이 있다면 누적거리는 기존 값 이상으로 설정합니다.

```sh
curl -X PUT "http://127.0.0.1:8088/vehicle/gear?gear=Park"
curl -X PUT "http://127.0.0.1:8088/vehicle/odometer?traveled_distance_km=1000"
```

### 3. 운행 시연

1. 앱의 **차량 연결** 카드에 심사용 영구 페어링 코드 **`71242776`**을 입력합니다.
2. 프로필 선택 → **주행 시작**을 누릅니다.
3. Virtual Console에서 기어를 **Drive**로 바꾸고 누적거리를 증가시킵니다.
4. 기어를 **Park**로 바꾼 뒤 앱에서 **운행 종료**를 누릅니다.
5. 최근 운행의 **운행 확인**에서 목적을 저장하고 전송 상태를 확인합니다.

주행 중에는 읽기 전용 운행 현황을 표시합니다. 주차만으로 운행이 자동 종료되지는 않습니다.

## 소스 빌드

JDK **17**, Android SDK **34**가 필요합니다. 루트 `.env`에 `AUTOLOG_SERVER_URL=https://<서버호스트>`를 설정한 뒤 빌드합니다.

```sh
./gradlew :app:assembleDebug -PconsoleOdometer=true
```

생성 APK: `app/build/outputs/apk/debug/app-debug.apk`

[상세 실행·문제 해결](docs/DEMO_ENVIRONMENT.md) · [상세 개발 안내](docs/DEVELOPMENT_NOTES.md)
