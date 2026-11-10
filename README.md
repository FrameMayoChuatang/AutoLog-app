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
app/src/main/java/com/b1nd/autolog
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
└─ ui/                      # Compose. 지금은 기능 확인용 기본 화면
```

## 설계 원칙 (에뮬레이터 검증 결과 반영)

- 운행 거리 = 종료 누적거리 − 시작 누적거리 (Trip API 미지원)
- 운행 시간 = 앱이 기록한 시작·종료 시각 (epoch ms 저장, 표시·출력은 KST)
- 주행 중 앱 화면은 차단됨 → 시작·종료·목적 선택은 P에서만
- 부팅 시 기록 서비스 자동 시작, 화면 자동 실행은 불가
- 프로세스가 죽으면 종료 이벤트가 없음 → 1초마다 저장, 다음 실행 시 INTERRUPTED로 복구

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
