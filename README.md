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

- 프로필 카드는 운전자를 선택하고, 별도 `운행 시작` 버튼으로 기존 운행 로직을 호출합니다.
- 이름 검색, 최근 기록의 운전자 우선 표시, 로컬 운전자 추가를 지원합니다.
- `P` 기어가 확인된 경우만 조작할 수 있습니다. 차량 연결과 유효한 누적거리 수신 전에는 시작 버튼이 비활성화됩니다.
- 주간/야간 테마와 분할 화면에 대응합니다. 프로필 터치 영역은 156dp, 주요 버튼 높이는 64~72dp이며 선택 상태는 테두리·체크·문구로 표시합니다.
- [Pleos 디자인 가이드](https://document.pleos.ai/docs/connect/guide/docs-design/Introduction/intro)를 참고했습니다. 현재 v2.1 에뮬레이터에는 `android.car` 공유 라이브러리가 없어 `CarUxRestrictions` 직접 연동은 미완료입니다. 앱은 DO를 선언하지 않아 기존 OS 차단 정책을 유지하며, 화면에서 추가로 P 여부를 확인합니다. 지원 이미지에서 정식 주행 제한 API 검증이 필요합니다.
- UI 테스트: `./gradlew :app:testDebugUnitTest --tests 'com.framemayo.autolog.ui.DriverSelectionScreenTest'`. 테스트용 운전자 데이터로 렌더링한 주간/야간 이미지는 `app/build/reports/driver-selection/`에 생성됩니다.

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
