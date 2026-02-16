# OpenClaw Health Uploader (Android)

Health Connect 데이터를 일(day) 단위로 집계해서 Supabase `ingest-health` Edge Function으로 업로드하는 Android 앱이야.

## 주요 동작 (v0.7)
- 앱 **최초 실행 시 자동으로 권한 플로우 시작**.
- Health Connect가 없으면 Play 스토어 설치 페이지로 이동.
- 권한이 이미 있으면 바로 다음 단계로 진행.
- 권한 허용 완료 후 앱으로 돌아오면 **최근 3개월 백필 업로드(90일)** 자동 실행.
- 백필 범위는 `day-1` ~ `day-90` (오늘 `day-0` 제외).
- 백필은 WorkManager 체인으로 하루씩 순차 업로드해서, 앱을 백그라운드로 두거나 화면이 꺼져도 계속 진행되도록 구성.
- 상태 텍스트에 진행률(`n/90`) 및 성공/실패 누적을 표시.
- 특정 날짜 업로드 실패가 있어도 중단하지 않고 계속 진행.
- 완료 후 `성공 X일, 실패 Y일` 요약 표시.
- 기존 WorkManager 자동 업로드(매일 09:05 근처, 전날 데이터)는 유지.

## 수동 동작
- `권한 확인/요청`: Health Connect 설치/권한 상태 확인 및 요청.
- `어제(day-1) 수동 업로드`: 전날 데이터 즉시 업로드.
- `대시보드 새로고침`: Supabase `health_daily` 최신 30건 다시 조회.
- 긴 작업(권한 요청 대기, 백필, 업로드, 새로고침) 중에는 버튼이 비활성화됨.

## 대시보드 조회 방식
- 1차: `INGEST_ENDPOINT`를 `GET`으로 호출 (`x-ingest-secret` 인증)
- 2차(401/실패 시): Supabase REST 직접 조회 (`SUPABASE_ANON_KEY` 사용)
- 따라서 함수 배포 지연/권한 이슈가 있어도 fallback으로 조회 가능.
- `INGEST_ENDPOINT` 형식은 아래 둘 다 지원:
  1. `https://<project-ref>.functions.supabase.co/ingest-health`
  2. `https://<project-ref>.supabase.co/functions/v1/ingest-health`

## 수집 항목
- 수면: `sleep_start`, `sleep_end`, `sleep_duration_minutes`
- 활동: `steps`, `distance_km`, `active_calories`, `workouts_count`

## 빌드 전 준비
루트에 `secrets.properties` (git 미추적) 파일이 필요해:

```properties
INGEST_ENDPOINT=https://<project-ref>.functions.supabase.co/ingest-health
INGEST_SECRET=<x-ingest-secret>
SUPABASE_ANON_KEY=<supabase anon key>
```

## 빌드
```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

## 개발 루프 자동화
### GitHub Actions CI
- 파일: `.github/workflows/android-ci.yml`
- push/PR마다 자동 실행:
  1) debug/release 빌드
  2) APK 아티팩트 업로드
  3) 에뮬레이터(API 34)에서 `connectedDebugAndroidTest` 스모크 테스트
- `main` push 시, `DISCORD_WEBHOOK_URL` 시크릿이 있으면 **release APK를 디스코드 웹훅으로 자동 전송**

### Firebase Test Lab (수동 트리거)
- 파일: `.github/workflows/firebase-test-lab.yml`
- `workflow_dispatch`로 실행
- 필요한 GitHub Secrets:
  - `GCP_SA_KEY`
  - `GCP_PROJECT_ID`
  - (선택) `INGEST_ENDPOINT`, `INGEST_SECRET`, `SUPABASE_ANON_KEY`
  - 배포 자동화용: `DISCORD_WEBHOOK_URL`

### 로컬 원클릭 루프
```bash
./scripts/dev_loop.sh
```
- 순서: debug 빌드 → 기기/에뮬레이터 스모크 테스트 → release 빌드

APK 출력:
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

## 알려진 제약
- Health Connect에 실제 데이터가 있어야 해당 날짜 값이 채워져.
- 백필은 최초 권한 완료 후 1회 수행되고, 실패한 날짜가 있어도 자동 재시도는 하지 않아.
- 자동 작업은 배터리 최적화/절전 정책에 따라 지연될 수 있어.
- 권한이 제거되면 자동 업로드는 skip되고, 앱에서 다시 권한 요청이 필요해.
- 시크릿 값(`INGEST_SECRET`)은 로그/화면에 출력하지 않아.
