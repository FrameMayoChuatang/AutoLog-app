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

## 구조

```
app/src/main/java/com/framemayo/autolog
├─ AutoLogApp.kt            # Application + 수동 DI(AppContainer)
├─ vehicle/                 # Pleos SDK는 여기서만 호출
│  ├─ VehicleDataSource.kt  # 누적거리·기어 Flow
│  ├─ OdometerValidator.kt  # 0·감소값 무시 (P0)
│  └─ StopDetector.kt       # 누적거리 45초 무변화 = 정차 후보 (P1)
├─ data/
│  ├─ local/                # Room: drivers, trips
│  └─ repository/           # TripRepository(시작·1초 저장·종료·복구), DriverRepository
├─ service/
│  ├─ TripRecorderService.kt # 포그라운드 서비스: 수집 + 1초 저장 + 미완료 복구
│  ├─ BootReceiver.kt        # 부팅 시 서비스 자동 시작
│  └─ RecorderState.kt       # 실시간 차량 상태 StateFlow
└─ ui/                      # Compose 운전자 선택 화면 + 기존 차량·기록 화면
```

## 설계 원칙 (에뮬레이터 검증 결과 반영)

- 운행 거리 = 종료 누적거리 − 시작 누적거리 (Trip API 미지원)
- 운행 시간 = 앱이 기록한 시작·종료 시각 (epoch ms 저장, 표시·출력은 KST)
- 주행 중 앱 화면은 차단됨 → 시작·종료·목적 선택은 P에서만
- 부팅 시 기록 서비스 자동 시작, 화면 자동 실행은 불가
- 프로세스가 죽으면 종료 이벤트가 없음 → 1초마다 저장, 다음 실행 시 INTERRUPTED로 복구

## 운전자 선택 UI

- 웹 미리보기에서 확정한 검은 배경과 원본 구름 캐릭터를 Compose로 이식했습니다. 로고·부서·최근 운전자 배지·별도 시작 버튼은 표시하지 않습니다.
- 프로필을 누르면 기존 운행 시작/위치 동의 흐름으로 바로 진입합니다. `P` 기어, 차량 연결, 유효한 누적거리가 확인되어야 시작할 수 있습니다. 연결 전에는 안내창의 `차량 상태 확인`으로 기존 페어링 화면에 접근합니다.
- 상단에는 운행 시각 기준 최근 운전자 최대 5명과 추가 버튼을 표시합니다. 동일 운전자의 여러 운행은 중복되지 않습니다. 운행 이력이 없는 운전자는 생성 시각 순서로 배치합니다.
- 나머지 프로필은 기본적으로 숨기고 최근 프로필 아래 `더보기`를 누르면 펼칩니다. 펼치면 `더보기` 버튼은 사라지고 추가 프로필만 표시합니다. 운전자가 5명 이하이면 버튼을 표시하지 않습니다. 검색 모달은 펼침 여부와 관계없이 전체 운전자를 검색합니다.
- 오른쪽 위 검색바를 누르면 검색 모달이 열리고, 전체 운전자의 이름 검색 결과는 모달 안에서만 표시합니다. 메인 화면의 최근 5명과 나머지 운전자 목록은 검색어에 영향을 받지 않습니다. 모달을 닫거나 결과를 선택하면 검색어를 비우며, 키보드만 닫을 때는 유지합니다.
- 왼쪽 위 `프로필 관리`에서 제목이 변경되고 각 카드에 수정/삭제 버튼이 나타납니다. 삭제는 확인창을 거치며 진행 중인 운전자는 삭제할 수 없습니다.
- `프로필 생성`/`프로필 수정` 모달은 최대 1,200×720dp로 표시하고 작은 화면에서는 영역 안에 맞춥니다. 이름을 실시간 미리보기하고 7가지 원형 색상과 7가지 표정(기본·미소·윙크·버럭·찡긋·빙글·느긋)을 각각 선택할 수 있습니다. 캐릭터 프리셋은 첨부 원본의 색상·표정 조합이며, 둘 중 하나라도 바꾸면 일치하지 않는 프리셋의 선택 표시를 해제합니다. 왼쪽 편집 영역만 스크롤하고, 좁은 화면에서는 세로로 배치합니다.
- 모든 표정은 같은 몸체 위에 눈·눈썹 레이어만 교체하므로 몸체의 색상·크기·위치는 유지됩니다. 표정 버튼은 연한 색상 배경에 눈만 표시합니다. 기본·윙크·버럭 표정은 열린 눈과 반사광을 함께 깜박이며 포인터 추적은 없습니다. 나머지 표정은 특유의 눈 모양을 보존하도록 정적으로 표시합니다. 깜박임은 편집 모달이 전면에 있을 때만 실행합니다. API 33 이상 하드웨어 캔버스에서 색상·깜박임을 지원하며, API 28–32와 소프트웨어 캔버스에서는 공통 하늘색 몸체와 선택한 눈을 정적으로 표시합니다.
- [Pleos 디자인 가이드](https://document.pleos.ai/docs/connect/guide/docs-design/Introduction/intro)를 참고했습니다. 현재 v2.1 에뮬레이터에는 `android.car` 공유 라이브러리가 없어 `CarUxRestrictions` 직접 연동은 미완료입니다. OS 주행 차단과 앱의 P 확인을 유지하며, 지원 이미지에서 정식 주행 제한 API 검증이 필요합니다.

- 목록 이미지는 화면 밀도에 따라 1/2 또는 1/4 크기로 디코딩하며 편집 미리보기는 원본 해상도를 유지합니다. 공통 몸체와 원본에서 분리한 투명 눈 레이어는 백그라운드에서 로딩하고 해상도별 최대 24MB 캐시로 공유합니다. 캐시에서 제거된 이미지도 현재 표시 중일 수 있으므로 강제로 recycle하지 않습니다.

### 로컬 프로필 설정과 서버 연동

- Room DB v10은 main과 프로필 시제품의 v8/v9 구조를 통합합니다. 기존 운전자·동의·운행·프로필 색상과 표정을 보존하고 누락된 열과 테이블만 추가합니다.
- 프로필 삭제는 `hidden` 상태로 목록에서 제외합니다. 서버 ID와 기존 운행 기록은 보존되며, 서버 운전자 목록을 다시 받아도 로컬 설정은 유지합니다. 현재 프로필 설정의 서버 업로드는 구현하지 않았습니다.
- 이후 서버가 프로필 설정을 관리하면 설정을 서버로 이전한 뒤 조회/저장 경로를 교체하고, 별도 마이그레이션에서 `driver_profiles`만 제거할 수 있습니다. 앱 DB 전체 초기화는 운행 기록을 잃으므로 사용하지 않습니다.
- 미리보기 브라우저의 샘플 프로필은 앱 DB로 복사하지 않습니다. 앱에 기존 저장된 운전자와 새로 생성한 프로필을 사용합니다.

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
- [ ] 정차 후보 시 종료 안내
- [ ] 서버 동기화 (오프라인 대기열)
- [ ] CarUxRestrictions 연동 (주행 제한 정식 대응)
- [ ] 국세청 운행기록부 출력은 서버/웹에서

### 공통 캐릭터 몸체 이미지

- `app/src/main/res/drawable-nodpi/driver_cloud_body.png`는 사용자가 제공한 하늘색 원본을 built-in imagegen의 편집 모드로 처리한 이미지입니다. 표정별 원본 몸체의 크기·색상 차이를 없애기 위해 눈만 제거한 공통 몸체를 사용합니다. 눈과 눈썹은 제공된 각 표정 원본에서 실행 중 분리하며 흰 반사광을 보존합니다.
- 편집 프롬프트: `Remove only the two brown oval eyes and their white highlights and shadows. Reconstruct seamless blue material beneath them. Preserve the exact original silhouette, framing, position, size, blue color, texture, lighting, white backdrop and four white cheek marks. No eyes, eyebrows, mouth, text or new objects.`
