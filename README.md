# OpenClaw Health Uploader (Android)

Health Connect 데이터를 일(day) 단위로 집계해서 Supabase `ingest-health` Edge Function으로 업로드하는 Android 앱이야.

이 프로젝트는 이제 **수면(sleep) 중심**으로 피벗했어:
- 앱 첫 화면에서 **수면 요약 카드**를 가장 먼저 보여줘 (총 수면, 수면 구간, 단계 분해, 간단 인사이트)
- 걸음/활동은 **2순위(부가 정보)**로 아래쪽에 작게 보여줘
- 화면 하단에 **바텀 네비게이션(대시보드/트렌드/설정)**을 둬서, 수면을 중심으로 보되 필요한 관리 기능은 설정 탭으로 분리했어.

## 주요 동작 (v0.9)
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
- **대시보드 탭**
  - 상단 `동기화` 버튼: Supabase `health_daily` 최신 30건 다시 조회 + 로컬(어제) 수면/활동 요약 갱신
- **설정 탭**
  - `권한 확인/요청`: Health Connect 설치/권한 상태 확인 및 요청
  - `대시보드 동기화`: 대시보드 탭과 동일하게 동기화 실행
  - `어제(day-1) 수동 업로드`: 전날 데이터 즉시 업로드
- 긴 작업(권한 요청 대기, 백필, 업로드, 새로고침) 중에는 버튼/동기화가 비활성화돼.

## 대시보드 조회 방식
- 1차: `INGEST_ENDPOINT`를 `GET`으로 호출 (`x-ingest-secret` 인증)
- 2차(401/실패 시): Supabase REST 직접 조회 (`SUPABASE_ANON_KEY` 사용)
- 따라서 함수 배포 지연/권한 이슈가 있어도 fallback으로 조회 가능.
- `INGEST_ENDPOINT` 형식은 아래 둘 다 지원:
  1. `https://<project-ref>.functions.supabase.co/ingest-health`
  2. `https://<project-ref>.supabase.co/functions/v1/ingest-health`

## 수집 항목
### 수면 (우선순위 1)
기본(v1, 기존 호환):
- `sleep_start`: 수면 세션 시작(ISO string)
- `sleep_end`: 수면 세션 종료(ISO string)
- `sleep_duration_minutes`: 수면 세션 구간(침대에 있던 시간) 분

확장(v2, Health Connect 수면 단계 기반):
- `sleep_minutes`: 실제 수면(= `light + deep + rem`) 분
- `sleep_awake_minutes`: 깨어있음 분
- `sleep_light_minutes`: 얕은 수면 분
- `sleep_deep_minutes`: 깊은 수면 분
- `sleep_rem_minutes`: REM 수면 분
- (선택/nullable) `sleep_score`: 수면 점수 (현재 앱에서는 미수집, null)
- (선택/nullable) `sleep_avg_hr`: 수면 중 평균 심박(bpm) (권한/데이터 있을 때만)
- (선택/nullable) `sleep_spo2`: 수면 중 SpO2 (현재 앱에서는 미수집, null)

백엔드 마이그레이션 전 안전장치:
- v2 필드는 기본으로 **payload의 `source.sleep_v2`** 아래에 넣어서 보냄 (기존 ingest 계약을 깨지 않게).
- 백엔드가 `health_daily`에 v2 컬럼을 추가한 뒤에는 `SEND_SLEEP_V2_FIELDS=true`로 설정하면 v2 키를 **top-level로도** 같이 보낼 수 있어.

### 활동 (우선순위 2)
- `steps`, `distance_km`, `active_calories`, `workouts_count`

## 빌드 전 준비
루트에 `secrets.properties` (git 미추적) 파일이 필요해:

```properties
INGEST_ENDPOINT=https://<project-ref>.functions.supabase.co/ingest-health
INGEST_SECRET=<x-ingest-secret>
SUPABASE_ANON_KEY=<supabase anon key>
# (선택) 백엔드가 v2 수면 컬럼을 지원할 때만 켜
SEND_SLEEP_V2_FIELDS=false
```

## 빌드
```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

## UI (Material 3)
- 테마: `Theme.Material3.DayNight.NoActionBar`
- Android 12+에서 지원되면 **Dynamic Color**(Material You)를 자동 적용해.
- 대시보드는 수면을 가장 위에 큰 카드로, 활동은 아래에 컴팩트 카드로 보여줘.

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

### 수면 단계가 안 보일 때 (중요)
Samsung Health 앱에서 수면 단계가 보이더라도, Health Connect를 통해 항상 동일하게 노출되는 건 아니야.
기기/앱 버전/연동 상태에 따라 `SleepSessionRecord.stages`(세션 안에 포함된 단계)가 비어있을 수 있어.

체크리스트:
1) 앱에서 Health Connect 권한이 제대로 허용됐는지 확인 (`READ_SLEEP`)
2) Health Connect에서 Samsung Health가 데이터 소스로 연결되어 있는지 확인
3) 해당 날짜에 실제로 수면 기록이 있는지 확인 (세션은 있는데 단계만 없을 수도 있음)
4) 수면이 자정/정오 경계를 걸칠 때 날짜 귀속이 기대와 다를 수 있어
5) 로그에서 `SleepDailyCollector` 태그로 `bestSession`, `stages=0` 같은 메시지를 확인해서
   "세션이 없는지" vs "세션은 있는데 단계(stages)가 없는지"를 구분해
