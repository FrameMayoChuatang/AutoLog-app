# 오토로그 (AutoLog) — 차량용 앱

법인차량 운행기록 자동 작성 서비스의 Pleos Connect(AAOS) 차량 앱.

## 개발 환경

| 항목 | 버전 |
|---|---|
| Android Studio | Koala 이상 |
| JDK | 17 (Android Studio 내장 JBR) |
| Gradle / AGP | 8.7 / 8.5.2 |
| Kotlin | 2.0.21 (Compose, KSP) |
| compileSdk / minSdk | 34 / 28 |
| Pleos Vehicle SDK | 2.3.2 |
| 에뮬레이터 | Pleos Connect v2.1.0+ (API 34) |

## 실행

1. Android Studio → Open → 이 폴더 → Gradle Sync
2. Pleos Connect AVD 실행 → ▶ Run
3. Virtual Console: `adb forward tcp:8088 tcp:8088` → http://127.0.0.1:8088/
   - Motion → 기어 / Odometer (누적거리는 줄일 수 없음, 현재값보다 크게)
4. 테스트: `./gradlew test`
5. 서버와 함께 시연 환경을 띄우는 방법: [docs/DEMO_ENVIRONMENT.md](docs/DEMO_ENVIRONMENT.md)

## 구조

```
app/src/main/java/com/framemayo/autolog
├─ AutoLogApp.kt            # Application + 수동 DI(AppContainer)
├─ vehicle/                 # Pleos SDK는 여기서만 호출
│  ├─ VehicleDataSource.kt  # 누적거리·기어 Flow
│  ├─ OdometerValidator.kt  # 0·감소값 무시 (P0)
│  ├─ MotionTracker.kt      # 주행/신호대기/주차 판단, 중간 정차·종료 후보·장시간 주차 자동 종료
│  ├─ AutoTripStarter.kt    # 이동 감지 자동 시작 판단
│  └─ EnergyEventDetector.kt # 주유·충전 감지
├─ data/
│  ├─ local/                # Room: drivers, trips
│  └─ repository/           # TripRepository(시작·1초 저장·종료·복구), DriverRepository
├─ service/
│  ├─ TripRecorderService.kt # 포그라운드 서비스: 수집 + 1초 저장 + 복구 + 정차·자동 시작·주유 감지
│  ├─ TripRecovery.kt        # 강제 종료 운행 재개/실측 완료/INTERRUPTED 판단
│  ├─ CollectorSupervisor.kt # SDK 초기화·구독 지수 백오프 재시도
│  ├─ AutoTripController.kt  # 자동 시작 운행 생성
│  ├─ BootReceiver.kt        # 부팅 시 서비스 자동 시작
│  └─ RecorderState.kt       # 실시간 차량 상태 StateFlow
└─ ui/                      # Compose 운전자 선택 화면 + 기존 차량·기록 화면
```

## 설계 원칙 (에뮬레이터 검증 결과 반영)

- 운행 거리 = 종료 누적거리 − 시작 누적거리 (Trip API 미지원)
- 운행 시간 = 앱이 기록한 시작·종료 시각 (epoch ms 저장, 표시·출력은 KST)
- 주행 중 앱 화면은 차단됨 → 시작·종료·목적 선택은 P에서만
- 부팅 시 기록 서비스 자동 시작, 화면 자동 실행은 불가
- 프로세스가 죽으면 종료 이벤트가 없음 → 1초마다 저장, 다음 실행 시 복구

## 수집·복구 (앱 A)

| 상황 | 동작 |
|---|---|
| 서비스 재시작, 마지막 저장 10분 이내 + 유효 누적거리 | 같은 운행 이어서 기록 (ACTIVE 유지) |
| 재시작 시 누적거리가 마지막 저장값과 같음(±0.05km) | 마지막 저장 시각·누적거리로 COMPLETED(`endedBy = RECOVERED`) |
| 누적거리를 못 읽음 | INTERRUPTED, 첫 유효 누적거리가 마지막 저장값과 같으면 늦게 COMPLETED |
| 정지 중 차가 움직였음 | INTERRUPTED 유지. 종료값을 추정하지 않음 (서버 계약) |
| SDK 초기화·구독 실패 | 1초부터 두 배씩 최대 30초 간격으로 재시도, 재구독 직후 누적거리 1회 조회. `RecorderState.collector`로 상태 노출 |
| 기어 P | 주차(PARKED). 운행 중이면 `trip_stops`에 정차 구간 기록, 같은 운행 유지 |
| P가 아니고 누적거리 45초 무변화 | 신호 대기(IDLE). 상태만 노출 |
| 운행 중 주차 5분 | `RecorderState.endCandidate = true` (종료 안내용) |
| 운행 중 주차 30분, 누적거리 변화 없음 | 주차 시작 시각·누적거리로 자동 종료 (`endedBy = AUTO_PARKED`) |
| 운행 없음 + P 아님 + 누적거리 0.05km 이상 증가 | 운전자 미지정 운행 자동 시작 (`startedBy = AUTO`), 시작값은 움직이기 전 기준 누적거리 |
| 정차 중 연료량 5%p 이상 증가 | 주유(REFUEL) 1건, 주유량·금액 추정 |
| 정차 중 배터리 3%p 이상 증가 또는 충전구 열림 중 증가 | 충전(CHARGE) 세션 1건, kWh·금액 추정 |

- 운행 시작·종료 시 연료량·배터리 잔량을 저장하고 `EnergyRepository.tripCost`로 운행별 사용량과 비용을 추정합니다. 단가·탱크 용량 기본값(`EnergyPricing`)은 추정용이며 서버로 전송하지 않습니다.
- 정차 구간·주유/충전 이벤트·자동 시작/종료 방식은 차량 로컬 정보입니다. 서버 전송 계약은 바뀌지 않습니다.
- Room DB v12에서 위 열과 `trip_stops`, `energy_events` 테이블을 추가합니다.

## 운전자 선택 UI

- 웹 미리보기에서 확정한 검은 배경과 원본 구름 캐릭터를 Compose로 이식했습니다. 로고·부서·최근 운전자 배지·별도 시작 버튼은 표시하지 않습니다.
- 프로필을 누르면 기존 운행 시작/위치 동의 흐름으로 바로 진입합니다. `P` 기어, 차량 연결, 유효한 누적거리가 확인되어야 시작할 수 있습니다. 연결 전에는 안내창의 `차량 상태 확인`으로 기존 페어링 화면에 접근합니다.
- `운전자는 나중에 선택`으로 미지정 운행을 시작할 수 있습니다. 주차·차량 연결·유효한 누적거리 확인은 동일하게 적용됩니다. 종료된 운행의 확인창에서 운전자를 검색해 지정합니다.
- 프로필 생성에 부서 입력은 제공하지 않습니다. 새 로컬 운전자는 기존 동기화 규칙에 따라 미지정 부서로 등록하며, 이름만으로 기존 서버 운전자를 자동 연결하지 않습니다.
- 상단에는 운행 시각 기준 최근 운전자 최대 5명과 추가 버튼을 표시합니다. 동일 운전자의 여러 운행은 중복되지 않습니다. 운행 이력이 없는 운전자는 생성 시각 순서로 배치합니다.
- 나머지 프로필은 기본적으로 숨기고 최근 프로필 아래 `더보기`를 누르면 펼칩니다. 펼치면 `더보기` 버튼은 사라지고 추가 프로필만 표시합니다. 운전자가 5명 이하이면 버튼을 표시하지 않습니다. 검색 모달은 펼침 여부와 관계없이 전체 운전자를 검색합니다.
- 오른쪽 위 검색바를 누르면 검색 모달이 열리고, 전체 운전자의 이름 검색 결과는 모달 안에서만 표시합니다. 메인 화면의 최근 5명과 나머지 운전자 목록은 검색어에 영향을 받지 않습니다. 모달을 닫거나 결과를 선택하면 검색어를 비우며, 키보드만 닫을 때는 유지합니다.
- 왼쪽 위 `프로필 관리`에서 제목이 변경되고 각 카드에 수정/삭제 버튼이 나타납니다. 삭제는 확인창을 거치며 진행 중인 운전자는 삭제할 수 없습니다.
- `프로필 생성`/`프로필 수정` 모달은 가로 배치에서 최대 1,200×600dp로 표시하고 작은 화면에서는 영역 안에 맞춥니다. 이름을 실시간 미리보기하고 7가지 원형 색상과 7가지 표정(기본·미소·윙크·버럭·찡긋·빙글·느긋)을 각각 선택할 수 있습니다. 캐릭터 프리셋은 첨부 원본의 색상·표정 조합이며, 둘 중 하나라도 바꾸면 일치하지 않는 프리셋의 선택 표시를 해제합니다. 색상·표정·캐릭터 선택지는 각각 한 줄로 가로 스크롤하며 줄바꿈하지 않습니다. 왼쪽 편집 영역만 세로 스크롤하고, 좁은 화면에서는 편집 영역과 미리보기를 위아래로 배치합니다.
- 모든 표정은 같은 몸체 위에 눈·눈썹 레이어만 교체하므로 몸체의 색상·크기·위치는 유지됩니다. 표정 버튼은 연한 색상 배경에 눈만 표시합니다. 기본·윙크·버럭 표정은 열린 눈과 반사광을 함께 깜박이며 포인터 추적은 없습니다. 나머지 표정은 특유의 눈 모양을 보존하도록 정적으로 표시합니다. 깜박임은 편집 모달이 전면에 있을 때만 실행합니다. API 33 이상 하드웨어 캔버스에서 색상·깜박임을 지원하며, API 28–32와 소프트웨어 캔버스에서는 공통 하늘색 몸체와 선택한 눈을 정적으로 표시합니다.
- [Pleos 디자인 가이드](https://document.pleos.ai/docs/connect/guide/docs-design/Introduction/intro)를 참고했습니다. 현재 v2.1 에뮬레이터에는 `android.car` 공유 라이브러리가 없어 `CarUxRestrictions` 직접 연동은 미완료입니다. OS 주행 차단과 앱의 P 확인을 유지하며, 지원 이미지에서 정식 주행 제한 API 검증이 필요합니다.

- 목록 이미지는 화면 밀도에 따라 1/2 또는 1/4 크기로 디코딩하며 편집 미리보기는 원본 해상도를 유지합니다. 공통 몸체와 원본에서 분리한 투명 눈 레이어는 백그라운드에서 로딩하고 해상도별 최대 24MB 캐시로 공유합니다. 캐시에서 제거된 이미지도 현재 표시 중일 수 있으므로 강제로 recycle하지 않습니다.

### 로컬 프로필 설정과 서버 연동

- Room DB v11은 v10 통합 구조에 선택한 업무 목적 문구(`trips.purposeText`)를 추가합니다. v10은 main과 프로필 시제품의 v8/v9 구조를 통합합니다. 기존 운전자·동의·운행·프로필 색상과 표정을 보존하고 누락된 열과 테이블만 추가합니다.
- 프로필 삭제는 `hidden` 상태로 목록에서 제외합니다. 서버 ID와 기존 운행 기록은 보존되며, 서버 운전자 목록을 다시 받아도 로컬 설정은 유지합니다. 현재 프로필 설정의 서버 업로드는 구현하지 않았습니다.
- 이후 서버가 프로필 설정을 관리하면 설정을 서버로 이전한 뒤 조회/저장 경로를 교체하고, 별도 마이그레이션에서 `driver_profiles`만 제거할 수 있습니다. 앱 DB 전체 초기화는 운행 기록을 잃으므로 사용하지 않습니다.
- 미리보기 브라우저의 샘플 프로필은 앱 DB로 복사하지 않습니다. 앱에 기존 저장된 운전자와 새로 생성한 프로필을 사용합니다.

### 운행 확인과 업무 목적

- 운행 종료 후 기록 화면을 유지하며 `운행 확인`에서 목적을 선택합니다. 확정 후에도 `확인 및 수정`으로 목적을 바꿀 수 있습니다.
- 업무 목적은 최대 200자로 직접 입력하거나 회사의 활성 목적 목록에서 선택합니다. 목록 조회가 실패해도 직접 입력과 로컬 저장은 가능합니다.
- 선택한 문구는 메모와 분리해 저장하고 서버 확인 요청의 `purposeText`로 전달합니다. 기존 업무 기록의 목적 문구가 비어 있으면 `일반 업무`로 해석합니다. 개인·출퇴근으로 변경하면 업무 목적 문구만 비우고 메모는 유지합니다.
- 운전자 없는 기록은 검색을 통해 운전자를 지정하며 지정 전 확인창을 거칩니다. 지정된 운전자는 차량에서 교체할 수 없습니다.
- 전송 상태는 완료·대기·보류·실패로 표시하고 보류 이유와 다음 조치를 안내합니다. 목적 미확인 기록은 측정값이 전송됐어도 목적 선택 안내를 유지합니다.

### 검증

- 전체 단위 테스트: `./gradlew :app:testDebugUnitTest`
- APK 빌드: `./gradlew :app:assembleDebug`
- UI 테스트: `./gradlew :app:testDebugUnitTest --tests 'com.framemayo.autolog.ui.DriverSelectionScreenTest'`
- DB 마이그레이션/프로필 설정/서버 목록 갱신 후 보존, 삭제 시 운행 보존과 진행 중 삭제 차단을 검증합니다.
- UI 테스트는 Robolectric SDK 35에서 실행합니다. SDK 34 테스트 엔진은 실제 API 34와 달리 이전 Skia를 사용해 child RuntimeShader를 지원하지 않습니다. 테스트 이미지 `app/build/reports/driver-selection/`는 소프트웨어 캔버스 기반 배치 확인용이며 색상·깜박임의 GPU 출력 검증을 대신하지 않습니다.

## 브랜치 / 커밋

- `main`: 시연 가능한 상태 유지
- 기능 브랜치: `feat/<기능>`, 버그: `fix/<내용>`
- 커밋: `feat: 운행 종료 시 목적 선택 추가` 형식

## TODO

- [ ] 운전자 선택 알림 (알림 센터 버튼) — `autoLog_test`의 DriverPrompt 이식
- [ ] 정차 후보 시 종료 안내 (앱 A는 `RecorderState.endCandidate` 제공, 안내 UI는 앱 B)
- [ ] 서버 동기화 (오프라인 대기열)
- [ ] CarUxRestrictions 연동 (주행 제한 정식 대응)
- [ ] 국세청 운행기록부 출력은 서버/웹에서

### 공통 캐릭터 몸체 이미지

- `app/src/main/res/drawable-nodpi/driver_cloud_body.png`는 사용자가 제공한 하늘색 원본을 built-in imagegen의 편집 모드로 처리한 이미지입니다. 표정별 원본 몸체의 크기·색상 차이를 없애기 위해 눈만 제거한 공통 몸체를 사용합니다. 눈과 눈썹은 제공된 각 표정 원본에서 실행 중 분리하며 흰 반사광을 보존합니다.
- 편집 프롬프트: `Remove only the two brown oval eyes and their white highlights and shadows. Reconstruct seamless blue material beneath them. Preserve the exact original silhouette, framing, position, size, blue color, texture, lighting, white backdrop and four white cheek marks. No eyes, eyebrows, mouth, text or new objects.`
