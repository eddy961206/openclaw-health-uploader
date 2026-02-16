# OpenClaw Health Uploader (Android)

Health Connect 데이터를 하루 단위로 집계해 Supabase `ingest-health`로 업로드하고,
앱 안에서 최근 데이터 대시보드를 바로 확인하는 앱.

## v0.7 주요 동작

### 1) 앱 첫 실행 시 자동 권한 흐름
- 앱을 처음 열면 버튼을 누르지 않아도 Health Connect 권한 확인을 자동으로 진행함.
- Health Connect 앱이 없으면 설치 페이지로 자동 이동.
- 권한 승인 후 앱으로 돌아오면 다음 단계(초기 백필) 자동 시작.

### 2) 초기 3개월(90일) 자동 백필 업로드
- 권한 승인 직후, 전날부터 90일 전까지(day-1 ~ day-90) 일괄 업로드.
- 진행 상태를 화면에 `n/90` 형태로 표시.
- 일부 날짜 실패해도 계속 진행하고, 마지막에 성공/실패 합계를 표시.
- 같은 백필은 앱 내부 플래그로 1회 완료 처리됨.

### 3) 일상 업로드
- 수동: `어제 데이터 업로드` 버튼
- 자동: WorkManager로 매일 09:05(로컬 시간) 근처 전날 데이터 업로드 예약

### 4) 앱 내 대시보드 (최근 30건)
- `대시보드 새로고침` 버튼으로 Supabase `health_daily` 조회
- 표시 컬럼:
  - 날짜
  - 수면(분)
  - 걸음수
  - 거리(km)
  - 활동칼로리
  - 운동횟수
- 로딩/비어있음/오류 상태 메시지 표시

## 현재 수집 항목
- 수면: `sleep_start`, `sleep_end`, `sleep_duration_minutes`
- 활동: `steps`, `distance_km`, `active_calories`, `workouts_count`

## 빌드 전 준비
`secrets.properties` (git 미추적)

```properties
INGEST_ENDPOINT=https://<project-ref>.supabase.co/functions/v1/ingest-health
# 또는 https://<project-ref>.functions.supabase.co/ingest-health
INGEST_SECRET=<x-ingest-secret>
```

## 빌드
```bash
./gradlew assembleDebug
./gradlew assembleRelease -x lint -x lintVitalRelease
```

## 출력 APK
- debug: `app/build/outputs/apk/debug/app-debug.apk`
- release: `app/build/outputs/apk/release/app-release.apk`

## 주의사항
- Health Connect에 실제 데이터가 있어야 업로드됨.
- 기기 절전/배터리 최적화 정책에 따라 자동 작업 시점은 지연될 수 있음.
- 권한이 해제되면 자동 업로드/조회가 정상 동작하지 않을 수 있음.
