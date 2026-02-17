#!/usr/bin/env python3
"""Generate a daily sleep-first health briefing from Supabase.

Reads Supabase URL + service_role key from:
  /Users/seung/.openclaw/credentials/health-supabase.json

Outputs a short Korean briefing.

Notes:
- Tries sleep v2 columns first (sleep_minutes + stages). If the backend hasn't migrated yet,
  it falls back to older selects automatically.
"""

import json
import os
import sys
import urllib.parse
import urllib.request
from datetime import datetime
from typing import Any, Dict, Optional, Tuple


CREDS_PATH = "/Users/seung/.openclaw/credentials/health-supabase.json"


def http_get(url: str, headers: Dict[str, str]) -> Tuple[int, str]:
    req = urllib.request.Request(url, headers=headers, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return resp.status, resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")


def parse_dt(s: Optional[str]) -> Optional[datetime]:
    if not s:
        return None
    # Accept both "...Z" and "+00:00"
    s = s.replace("Z", "+00:00")
    try:
        return datetime.fromisoformat(s)
    except Exception:
        return None


def fmt_minutes(m: Any) -> Optional[str]:
    if m is None:
        return None
    try:
        m = int(float(m))
    except Exception:
        return None
    h = m // 60
    mm = m % 60
    return f"{h}시간 {mm}분" if h else f"{mm}분"


def fetch_latest(base: str, key: str, select: str) -> Optional[Dict[str, Any]]:
    params = {"select": select, "order": "day.desc", "limit": "1"}
    url = f"{base}/rest/v1/health_daily?{urllib.parse.urlencode(params)}"
    headers = {"apikey": key, "Authorization": f"Bearer {key}", "Accept": "application/json"}

    status, body = http_get(url, headers)
    if status != 200:
        return None
    data = json.loads(body)
    if not data:
        return {}
    return data[0]


def build_insight(d: dict) -> str:
    sleep_min = d.get("sleep_minutes")
    deep = d.get("sleep_deep_minutes")
    rem = d.get("sleep_rem_minutes")
    light = d.get("sleep_light_minutes")
    awake = d.get("sleep_awake_minutes")

    # If v2 not present, try the old duration.
    if sleep_min is None:
        sleep_min = d.get("sleep_duration_minutes")

    try:
        sleep_min = int(float(sleep_min)) if sleep_min is not None else None
        deep = int(float(deep)) if deep is not None else None
        rem = int(float(rem)) if rem is not None else None
        light = int(float(light)) if light is not None else None
        awake = int(float(awake)) if awake is not None else None
    except Exception:
        pass

    if not sleep_min:
        return "수면 기록이 없거나 수집이 안 됐어"

    if deep is None and rem is None and light is None and awake is None:
        return "수면 단계 데이터가 없어서 총 수면만 기준으로 봐야 해"

    parts = []
    if deep is not None and deep >= 0:
        deep_pct = deep / float(sleep_min) if sleep_min > 0 else 0.0
        if deep_pct < 0.10:
            parts.append("깊은 수면 비율이 낮은 편")
        elif deep_pct > 0.35:
            parts.append("깊은 수면 비율이 높은 편")
    if rem is not None and rem >= 0:
        rem_pct = rem / float(sleep_min) if sleep_min > 0 else 0.0
        if rem_pct < 0.15:
            parts.append("렘 수면 비율이 낮은 편")
        elif rem_pct > 0.35:
            parts.append("렘 수면 비율이 높은 편")

    return ", ".join(parts) if parts else "단계 밸런스가 무난해 보여"


def main() -> int:
    if not os.path.exists(CREDS_PATH):
        print("health_brief: creds missing")
        return 2

    creds = json.load(open(CREDS_PATH, "r", encoding="utf-8"))
    base = creds["projectUrl"].rstrip("/")
    key = creds["serviceRoleKey"]

    # Try v2 sleep columns first. If the backend hasn't migrated, this select can fail.
    d = fetch_latest(
        base,
        key,
        "day,sleep_start,sleep_end,sleep_minutes,sleep_awake_minutes,sleep_light_minutes,"
        "sleep_deep_minutes,sleep_rem_minutes,sleep_score,sleep_avg_hr,sleep_spo2,"
        "sleep_duration_minutes,steps,active_calories,workouts_count,distance_km,notes",
    )
    if d is None:
        d = fetch_latest(
            base,
            key,
            "day,sleep_start,sleep_end,sleep_duration_minutes,steps,active_calories,workouts_count,distance_km,notes",
        )

    if d is None:
        print("health_brief: fetch failed (schema/permission/network).")
        return 1
    if d == {}:
        print("오늘 건강 데이터가 아직 안 들어옴. (수면 업로드 파이프라인 확인 필요)")
        return 0

    day = d.get("day") or "-"
    sleep_start = parse_dt(d.get("sleep_start"))
    sleep_end = parse_dt(d.get("sleep_end"))
    sleep_min = d.get("sleep_minutes")
    if sleep_min is None:
        sleep_min = d.get("sleep_duration_minutes")

    deep = d.get("sleep_deep_minutes")
    rem = d.get("sleep_rem_minutes")
    light = d.get("sleep_light_minutes")
    awake = d.get("sleep_awake_minutes")

    avg_hr = d.get("sleep_avg_hr")
    spo2 = d.get("sleep_spo2")
    notes = d.get("notes")

    steps = d.get("steps")
    active_calories = d.get("active_calories")
    workouts_count = d.get("workouts_count")
    distance_km = d.get("distance_km")

    lines = [f"[{day}] 수면 브리핑"]

    # Sleep first
    dur_txt = fmt_minutes(sleep_min)
    lines.append(f"- 총 수면: {dur_txt or '-'}")

    if sleep_start and sleep_end:
        # Convert to local timezone for readability.
        s_local = sleep_start.astimezone()
        e_local = sleep_end.astimezone()
        lines.append(f"- 수면 구간: {s_local:%H:%M} ~ {e_local:%H:%M}")

    stage_parts = []
    if deep is not None:
        stage_parts.append(f"깊 {fmt_minutes(deep) or '-'}")
    if rem is not None:
        stage_parts.append(f"렘 {fmt_minutes(rem) or '-'}")
    if light is not None:
        stage_parts.append(f"얕 {fmt_minutes(light) or '-'}")
    if awake is not None:
        stage_parts.append(f"깸 {fmt_minutes(awake) or '-'}")
    if stage_parts:
        lines.append("- 단계: " + ", ".join(stage_parts))

    lines.append(f"- 인사이트: {build_insight(d)}")

    if avg_hr is not None:
        try:
            lines.append(f"- 수면 중 평균 심박: {float(avg_hr):.0f} bpm")
        except Exception:
            pass
    if spo2 is not None:
        try:
            lines.append(f"- 수면 중 SpO2: {float(spo2):.1f}%")
        except Exception:
            pass

    # Activity (secondary)
    lines.append("")
    lines.append("[활동(요약)]")
    if steps is not None:
        try:
            lines.append(f"- 걸음수: {int(float(steps)):,} 걸음")
        except Exception:
            pass
    if distance_km is not None:
        try:
            lines.append(f"- 이동거리: {float(distance_km):.2f} km")
        except Exception:
            pass
    if active_calories is not None:
        try:
            lines.append(f"- 활동칼로리: {float(active_calories):.0f} kcal")
        except Exception:
            pass
    if workouts_count is not None:
        try:
            lines.append(f"- 운동 세션: {int(float(workouts_count))}회")
        except Exception:
            pass

    if notes:
        lines.append("")
        lines.append(f"- 메모: {notes}")

    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
