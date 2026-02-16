# OpenClaw Health Uploader (Android)

Health Connect 데이터를 하루 단위로 집계해서 Supabase `ingest-health` Edge Function으로 올리는 Android 앱.

## 현재 수집 항목 (v0.2)
- 수면: `sleep_start`, `sleep_end`, `sleep_duration_minutes`
- 활동: `steps`, `distance_km`, `active_calories`, `workouts_count`

> 참고: `rhr_bpm`, `hrv_ms`, 수면 단계(REM/Deep/Light)는 Health Connect에 실제 기록이 있을 때만 추가 확장 가능.

## 자동 업로드
- WorkManager 기반으로 **매일 09:05(로컬 시간) 근처** 자동 업로드 예약.
- 업로드 대상은 **전날(day-1)** 데이터.
- 앱 최초 실행 후 권한 허용이 필요함.

## 수동 업로드
- 앱에서 `어제 데이터 업로드` 버튼으로 즉시 업로드 가능.

## 앱 내 대시보드 (v0.6)
- 메인 화면에서 `대시보드 새로고침` 버튼으로 Supabase `health_daily` 최신 30건 조회.
- 별도 키 없이 기존 `INGEST_ENDPOINT` + `INGEST_SECRET` 값을 재사용해 조회.
- 조회 중에는 `불러오는 중` 텍스트가 표시됨.
- 데이터가 없으면 `데이터 없음` 텍스트가 표시됨.
- 네트워크/키/권한 문제면 `대시보드 오류` 텍스트가 표시됨.
- 목록에는 `day`, `steps`, `distance_km`, `active_calories`, `workouts_count`, `sleep_duration_minutes`를 표시.

## 빌드 전 준비
`secrets.properties` (git 미추적) 파일 필요:

```properties
INGEST_ENDPOINT=https://<project-ref>.functions.supabase.co/ingest-health
INGEST_SECRET=<x-ingest-secret>
```

## 빌드
```bash
./gradlew assembleDebug
```

APK 출력:
- `app/build/outputs/apk/debug/app-debug.apk`

## 권한/제약 사항
- Health Connect에 해당 데이터가 실제로 저장되어 있어야 업로드됨.
- 자동 작업은 배터리 최적화/절전 정책에 따라 약간 지연될 수 있음.
- 권한이 제거되면 자동 업로드는 skip됨 (앱에서 다시 권한 허용 필요).
