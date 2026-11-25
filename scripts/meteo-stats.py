#!/usr/bin/env python3
"""Open-Meteo call, timeout, HTTP-fallback, phase-failure, and retry stats across desktop and adb devices.

Reads:
  Desktop  ~/.local/share/weather-widget/weather.db (app_logs + api_usage_stats)
           ~/.local/state/weather-widget/autostart-*.log (OpenMeteoApi / HttpRetry / HTTP_PHASE_FAIL)
  Android  For every connected `adb devices` target (via read-only `run-as com.weatherwidget`):
           Room DB (weather_database + -wal + -shm) for app_logs + api_usage_stats,
           plus `logcat -d` for live OpenMeteoApi / HttpRetry / HTTP_PHASE_FAIL lines.

Usage:
  scripts/open-meteo-stats.py [--days 7] [--since YYYY-MM-DD[THH:MM]]
                              [--no-desktop] [--no-devices] [--serial SERIAL]
                              [--episodes 10]
"""

import argparse
import glob
import os
import re
import shutil
import sqlite3
import statistics
import subprocess
import sys
import tempfile
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta

PACKAGE = "com.weatherwidget"
DESKTOP_DB = os.path.expanduser("~/.local/share/weather-widget/weather.db")
DESKTOP_LOG_DIR = os.path.expanduser("~/.local/state/weather-widget")
DB_FILES = ("weather_database", "weather_database-wal", "weather_database-shm")


def adb_bin():
    return (
        os.environ.get("ADB_BIN")
        or shutil.which("adb")
        or os.path.expanduser("~/.Android/Sdk/platform-tools/adb")
    )


def connected_devices(only_serial=None):
    """Returns (ready serials, [(serial, state)] adb lists but cannot use, e.g. offline/unauthorized)."""
    adb = adb_bin()
    if not adb or not os.path.exists(adb):
        print(f"   (adb not found at {adb})", file=sys.stderr)
        return [], []
    try:
        out = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=15).stdout
    except Exception as e:
        print(f"   (adb devices failed: {e})", file=sys.stderr)
        return [], []
    ready, not_ready = [], []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) < 2:
            continue
        (ready if parts[1] == "device" else not_ready).append(parts[0] if parts[1] == "device" else (parts[0], parts[1]))
    if only_serial:
        ready = [s for s in ready if s == only_serial]
        not_ready = [(s, st) for s, st in not_ready if s == only_serial]
    return ready, not_ready


def device_label(serial):
    try:
        model = subprocess.run(
            [adb_bin(), "-s", serial, "shell", "getprop", "ro.product.model"],
            capture_output=True, text=True, timeout=10,
        ).stdout.strip()
    except Exception:
        model = ""
    return f"{model or serial} ({serial})"


def pull_device_db(serial, dest_dir):
    """Returns (db path, None) or (None, reason). run-as fails e.g. while installDebug swaps the APK."""
    main_err = None
    for name in DB_FILES:
        dest = os.path.join(dest_dir, name)
        try:
            with open(dest, "wb") as f:
                res = subprocess.run(
                    [adb_bin(), "-s", serial, "exec-out", "run-as", PACKAGE, "cat", f"databases/{name}"],
                    stdout=f, stderr=subprocess.PIPE, timeout=45,
                )
        except subprocess.TimeoutExpired:
            if name == DB_FILES[0]:
                return None, f"pulling {name} timed out after 45s"
            continue
        if name == DB_FILES[0] and res.returncode != 0:
            main_err = (res.stderr or b"").decode(errors="replace").strip() or f"run-as exit {res.returncode}"
    path = os.path.join(dest_dir, DB_FILES[0])
    with open(path, "rb") as f:
        header = f.read(16)
    if header != b"SQLite format 3\x00":
        # exec-out writes run-as errors to stdout on some devices, so read them from the file too.
        detail = main_err or header.decode(errors="replace").strip() or "empty file"
        if not main_err and os.path.getsize(path) > 0:
            with open(path, "rb") as f:
                detail = f.read(300).decode(errors="replace").strip()
        return None, f"no readable {DB_FILES[0]}: {detail}"
    return path, None


def pull_device_logcat(serial):
    try:
        res = subprocess.run(
            [adb_bin(), "-s", serial, "logcat", "-d", "-v", "time",
             "OpenMeteoApi:D", "HttpRetry:W", "HTTP_PHASE_FAIL:W", "OPEN_METEO_HTTP_FALLBACK:W", "*:S"],
            capture_output=True, text=True, errors="replace", timeout=20,
        )
        return res.stdout.splitlines()
    except Exception:
        return []


def parse_kv(msg):
    out = {}
    for tok in msg.split():
        if "=" in tok:
            k, v = tok.split("=", 1)
            out[k] = v
    return out


def pct(vals, p):
    if not vals:
        return None
    s = sorted(vals)
    idx = min(len(s) - 1, max(0, int(round((p / 100.0) * (len(s) - 1)))))
    return s[idx]


def classify_stalled_phase(kv):
    def iv(k):
        try:
            return int(kv.get(k, "-1"))
        except ValueError:
            return -1

    if iv("dnsMs") < 0 and not kv.get("reused") == "true":
        return "dns"
    if iv("connectMs") < 0 and kv.get("reused") != "true":
        return "tcp_connect"
    if kv.get("scheme") == "https" and iv("tlsMs") < 0 and kv.get("reused") != "true":
        return "tls"
    if iv("reqHeadersMs") < 0:
        return "req_headers"
    if iv("ttfbMs") < 0:
        return "ttfb"
    return "body/other"


def analyze_db(db_path, since_ms):
    conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    res = {
        "usage": {},
        "http_fallback": [],
        "phase_fails": [],
        "retry_scheduled": Counter(),
        "retry_fired": 0,
        "retry_recovered": [],
        "retry_skipped": Counter(),
        "episodes": [],
        "status_ok": 0,
        "status_fail": 0,
        "status_fail_classes": Counter(),
    }
    try:
        # 1. api_usage_stats
        since_day_ms = int(datetime.fromtimestamp(since_ms / 1000).replace(
            hour=0, minute=0, second=0, microsecond=0
        ).timestamp() * 1000)
        try:
            for endpoint, calls, errs in conn.execute(
                "SELECT endpoint, SUM(callCount), SUM(errorCount) FROM api_usage_stats "
                "WHERE apiSource = 'OPEN_METEO' AND date >= ? GROUP BY endpoint ORDER BY endpoint",
                (since_day_ms,),
            ):
                res["usage"][endpoint or "(none)"] = (calls or 0, errs or 0)
        except sqlite3.OperationalError:
            pass

        # 2. app_logs
        rows = conn.execute(
            "SELECT timestamp, level, tag, message FROM app_logs "
            "WHERE timestamp >= ? AND ("
            "  tag IN ('OPEN_METEO_HTTP_FALLBACK', 'HTTP_PHASE_FAIL', 'SOURCE_RETRY', 'REFRESH_FAIL', 'CURRENT_TEMP_STATUS', 'FORECAST_FETCH')"
            "  OR message LIKE '%OPEN_METEO%' OR message LIKE '%open-meteo%'"
            ") ORDER BY timestamp ASC",
            (since_ms,),
        ).fetchall()

        # Track failure -> recovery episodes for OPEN_METEO
        current_ep = None
        for ts, _lvl, tag, msg in rows:
            if tag == "OPEN_METEO_HTTP_FALLBACK":
                kv = parse_kv(msg)
                res["http_fallback"].append(kv)
            elif tag == "HTTP_PHASE_FAIL":
                if "open-meteo" in msg:
                    res["phase_fails"].append(parse_kv(msg))
            elif tag == "SOURCE_RETRY" and "source=OPEN_METEO" in msg:
                kv = parse_kv(msg)
                if msg.startswith("scheduled "):
                    res["retry_scheduled"][kv.get("attempt", "?")] += 1
                    if current_ep is None:
                        current_ep = {
                            "start_ms": ts,
                            "last_fail_ms": ts,
                            "trigger": kv.get("trigger", "?"),
                            "code": kv.get("code", "?"),
                            "failures": 1,
                            "retries_fired": 0,
                            "recovered_ms": None,
                        }
                    else:
                        if ts - current_ep["last_fail_ms"] > 2_000:
                            current_ep["failures"] += 1
                            current_ep["last_fail_ms"] = ts
                        if current_ep["trigger"] == "fetch":
                            current_ep["trigger"] = kv.get("trigger", "?")
                elif msg.startswith("fire "):
                    res["retry_fired"] += 1
                    if current_ep is not None:
                        current_ep["retries_fired"] += 1
                elif msg.startswith("recovered "):
                    try:
                        res["retry_recovered"].append(int(kv.get("elapsedMs", "-1")))
                    except ValueError:
                        pass
                    if current_ep is not None:
                        current_ep["recovered_ms"] = ts
                        res["episodes"].append(current_ep)
                        current_ep = None
                elif msg.startswith("skip ") or msg.startswith("fire_skip "):
                    res["retry_skipped"][kv.get("reason", "?")] += 1
            elif tag in ("CURRENT_TEMP_STATUS", "FORECAST_FETCH") and "source=OPEN_METEO" in msg:
                kv = parse_kv(msg)
                if kv.get("ok") == "true":
                    if tag == "CURRENT_TEMP_STATUS":
                        res["status_ok"] += 1
                    if current_ep is not None:
                        current_ep["recovered_ms"] = ts
                        res["episodes"].append(current_ep)
                        current_ep = None
                elif kv.get("ok") == "false":
                    cls = kv.get("class", kv.get("code", "?"))
                    # Ignore older desktop Unknown placeholder rows for episode tracking
                    if cls != "Unknown":
                        if tag == "CURRENT_TEMP_STATUS":
                            res["status_fail"] += 1
                            res["status_fail_classes"][cls] += 1
                        if current_ep is None:
                            current_ep = {
                                "start_ms": ts,
                                "last_fail_ms": ts,
                                "trigger": "fetch",
                                "code": cls,
                                "failures": 1,
                                "retries_fired": 0,
                                "recovered_ms": None,
                            }
                        elif ts - current_ep["start_ms"] > 30 * 60_000:
                            res["episodes"].append(current_ep)
                            current_ep = {
                                "start_ms": ts,
                                "last_fail_ms": ts,
                                "trigger": "fetch",
                                "code": cls,
                                "failures": 1,
                                "retries_fired": 0,
                                "recovered_ms": None,
                            }
                        elif ts - current_ep["last_fail_ms"] > 2_000:
                            current_ep["failures"] += 1
                            current_ep["last_fail_ms"] = ts

        if current_ep is not None:
            res["episodes"].append(current_ep)
    finally:
        conn.close()
    return res


def analyze_text_lines(lines):
    """Parses OpenMeteoApi / HttpRetry / HTTP_PHASE_FAIL lines from console logs or logcat."""
    stats = {
        "clean_ok_ms": [],
        "fallback_ok_ms": [],
        "https_fails": 0,
        "forecast_fails": 0,
        "http_retries": 0,
        "phase_fails": [],
    }
    raw_re = re.compile(r"OpenMeteoApi: getForecast: Raw response length=\d+ ms=(\d+)")
    pending_https_fail = False
    for line in lines:
        if "OpenMeteoApi: https failed" in line:
            stats["https_fails"] += 1
            if "minutely_15=" in line or "daily=" in line:
                pending_https_fail = True
        elif "OpenMeteoApi: getForecast: Raw response length=" in line:
            m = raw_re.search(line)
            ms = int(m.group(1)) if m else None
            if pending_https_fail:
                if ms is not None:
                    stats["fallback_ok_ms"].append(ms)
                pending_https_fail = False
            else:
                if ms is not None:
                    stats["clean_ok_ms"].append(ms)
        elif "OpenMeteoApi: getForecast: failed" in line:
            stats["forecast_fails"] += 1
            pending_https_fail = False
        elif "HttpRetry: retryOnException" in line and "open-meteo" in line:
            stats["http_retries"] += 1
        elif "HTTP_PHASE_FAIL:" in line and "open-meteo" in line:
            msg = line.split("HTTP_PHASE_FAIL:", 1)[1].strip()
            stats["phase_fails"].append(parse_kv(msg))
    return stats


def read_desktop_console_lines(since_dt):
    paths = sorted(glob.glob(os.path.join(DESKTOP_LOG_DIR, "autostart-*.log")))
    lines = []
    since_ts = since_dt.timestamp()
    for p in paths:
        try:
            if os.path.getmtime(p) < since_ts:
                continue
            with open(p, "r", errors="replace") as f:
                for line in f:
                    if ("OpenMeteoApi" in line or "HTTP_PHASE_FAIL" in line
                            or ("HttpRetry" in line and "open-meteo" in line)):
                        lines.append(line)
        except OSError:
            continue
    return lines


def print_phase_breakdown(phase_fails, indent="   "):
    if not phase_fails:
        return
    by_phase = Counter(classify_stalled_phase(kv) for kv in phase_fails)
    by_ip = Counter(kv.get("remoteIp", "none") for kv in phase_fails)
    by_proto = Counter(kv.get("protocol", "none") for kv in phase_fails)
    by_scheme = Counter(kv.get("scheme", "?") for kv in phase_fails)
    reused = sum(1 for kv in phase_fails if kv.get("reused") == "true")
    print(f"{indent}HTTP_PHASE_FAIL ({len(phase_fails)} events, reused={reused}):")
    print(f"{indent}  stalled phase : " + ", ".join(f"{k}={v}" for k, v in by_phase.most_common()))
    print(f"{indent}  scheme / proto: " + ", ".join(f"{k}={v}" for k, v in by_scheme.most_common())
          + " | " + ", ".join(f"{k}={v}" for k, v in by_proto.most_common()))
    print(f"{indent}  remoteIp      : " + ", ".join(f"{k}={v}" for k, v in by_ip.most_common(5)))


def print_report(label, db_stats, text_stats, text_label, max_episodes):
    print(f"\n== {label}")
    if db_stats is None and text_stats is None:
        print("   (no data accessible)")
        return

    if db_stats and db_stats["usage"]:
        parts = [f"{ep}={c} (err={e})" for ep, (c, e) in db_stats["usage"].items()]
        print("   api_usage_stats : " + ", ".join(parts))

    if text_stats:
        clean = text_stats["clean_ok_ms"]
        fb = text_stats["fallback_ok_ms"]
        clean_lat = (
            f"med={int(statistics.median(clean))}ms p95={pct(clean, 95)}ms" if clean else "n/a"
        )
        fb_lat = f"med={int(statistics.median(fb))}ms" if fb else "n/a"
        print(
            f"   {text_label:<16}: clean_https_ok={len(clean)} ({clean_lat}), "
            f"https_fail={text_stats['https_fails']}, "
            f"http_fallback_ok={len(fb)} ({fb_lat}), "
            f"forecast_fail={text_stats['forecast_fails']}, "
            f"ktor_retry={text_stats['http_retries']}"
        )

    if db_stats:
        fail_cls = ", ".join(f"{k}={v}" for k, v in db_stats["status_fail_classes"].most_common()) or "none"
        print(f"   CURRENT_TEMP    : ok={db_stats['status_ok']}, fail={db_stats['status_fail']} ({fail_cls})")

        if db_stats["http_fallback"]:
            https_ms = [int(kv["httpsMs"]) for kv in db_stats["http_fallback"] if kv.get("httpsMs", "").isdigit()]
            http_ms = [int(kv["httpMs"]) for kv in db_stats["http_fallback"] if kv.get("httpMs", "").isdigit()]
            causes = Counter(kv.get("cause", "?") for kv in db_stats["http_fallback"])
            h1 = f"{int(statistics.median(https_ms))}ms" if https_ms else "?"
            h2 = f"{int(statistics.median(http_ms))}ms" if http_ms else "?"
            print(
                f"   HTTP_FALLBACK   : count={len(db_stats['http_fallback'])} "
                f"(med https={h1}, http={h2}; " + ", ".join(f"{k}={v}" for k, v in causes.most_common()) + ")"
            )

        sched_total = sum(db_stats["retry_scheduled"].values())
        if sched_total or db_stats["retry_fired"] or db_stats["retry_recovered"]:
            rec = db_stats["retry_recovered"]
            rec_str = f"{len(rec)} (med={int(statistics.median(rec))}ms)" if rec else "0"
            skips = ", ".join(f"{k}={v}" for k, v in db_stats["retry_skipped"].most_common()) or "none"
            print(
                f"   SOURCE_RETRY    : scheduled={sched_total} {dict(sorted(db_stats['retry_scheduled'].items()))}, "
                f"fired={db_stats['retry_fired']}, recovered={rec_str}, skipped=({skips})"
            )

        phase_fails = db_stats["phase_fails"] or (text_stats["phase_fails"] if text_stats else [])
        print_phase_breakdown(phase_fails)

        eps = db_stats["episodes"][-max_episodes:]
        if eps:
            print(f"   Recent timeout/retry episodes (last {len(eps)} of {len(db_stats['episodes'])}):")
            for ep in reversed(eps):
                ts_str = datetime.fromtimestamp(ep["start_ms"] / 1000).strftime("%Y-%m-%d %H:%M:%S")
                if ep["recovered_ms"] is not None:
                    rec_s = (ep["recovered_ms"] - ep["start_ms"]) / 1000.0
                    rec_txt = f"recovered in {rec_s:.1f}s"
                else:
                    rec_txt = "UNRECOVERED"
                print(
                    f"     {ts_str} | {ep['code']:<18} | fails={ep['failures']} retries={ep['retries_fired']} | {rec_txt}"
                )
    elif text_stats and text_stats["phase_fails"]:
        print_phase_breakdown(text_stats["phase_fails"])


def parse_since(args):
    if args.since:
        for fmt in ("%Y-%m-%dT%H:%M", "%Y-%m-%d %H:%M", "%Y-%m-%d"):
            try:
                return datetime.strptime(args.since, fmt)
            except ValueError:
                continue
        raise SystemExit(f"Invalid --since format: {args.since} (use YYYY-MM-DD or YYYY-MM-DDTHH:MM)")
    return datetime.now() - timedelta(days=args.days)


def summary_row(label, db_stats, text_stats):
    """Computes one summary table row dict for a platform."""
    fc_calls, fc_errs = (0, 0)
    if db_stats and db_stats["usage"]:
        fc_calls, fc_errs = db_stats["usage"].get("forecast", (0, 0))
    # For older desktop logs before HttpSend error accounting, fall back to status_fail when usage err=0.
    errs = fc_errs if fc_errs > 0 else (db_stats["status_fail"] if db_stats else 0)
    total_attempts = fc_calls + (errs if fc_errs == 0 and errs > 0 else 0)
    err_pct = f"{(100.0 * errs / total_attempts):.1f}%" if total_attempts > 0 else "-"

    clean_ms = text_stats["clean_ok_ms"] if text_stats else []
    med_ms = f"{int(statistics.median(clean_ms))}ms" if clean_ms else "-"

    fb_db = len(db_stats["http_fallback"]) if db_stats else 0
    fb_txt = len(text_stats["fallback_ok_ms"]) if text_stats else 0
    fb_count = max(fb_db, fb_txt)

    sched = sum(db_stats["retry_scheduled"].values()) if db_stats else 0
    fired = db_stats["retry_fired"] if db_stats else 0
    phase_n = len(db_stats["phase_fails"]) if db_stats and db_stats["phase_fails"] else (
        len(text_stats["phase_fails"]) if text_stats else 0
    )

    rec_secs = []
    if db_stats:
        for ep in db_stats["episodes"]:
            if ep["recovered_ms"] is not None:
                dt = (ep["recovered_ms"] - ep["start_ms"]) / 1000.0
                # Exclude multi-hour overnight gaps when computing median retry recovery.
                if dt <= 3600.0:
                    rec_secs.append(dt)
    med_rec = f"{statistics.median(rec_secs):.1f}s" if rec_secs else "-"

    return {
        "label": label,
        "calls": total_attempts,
        "errs": errs,
        "err_pct": err_pct,
        "med_ms": med_ms,
        "fb": fb_count,
        "sched": sched,
        "fired": fired,
        "phase_n": phase_n,
        "rec_secs": rec_secs,
        "med_rec": med_rec,
    }


def print_summary_table(results):
    if not results:
        return
    rows = [summary_row(label, db_s, txt_s) for label, db_s, txt_s, _ in results]
    tot_calls = sum(r["calls"] for r in rows)
    tot_errs = sum(r["errs"] for r in rows)
    tot_fb = sum(r["fb"] for r in rows)
    tot_sched = sum(r["sched"] for r in rows)
    tot_fired = sum(r["fired"] for r in rows)
    tot_phase = sum(r["phase_n"] for r in rows)
    all_rec = [s for r in rows for s in r["rec_secs"]]
    tot_row = {
        "label": "TOTAL",
        "calls": tot_calls,
        "errs": tot_errs,
        "err_pct": f"{(100.0 * tot_errs / tot_calls):.1f}%" if tot_calls > 0 else "-",
        "med_ms": "-",
        "fb": tot_fb,
        "sched": tot_sched,
        "fired": tot_fired,
        "phase_n": tot_phase,
        "med_rec": f"{statistics.median(all_rec):.1f}s" if all_rec else "-",
    }

    name_w = max(len("Platform"), max(len(r["label"]) for r in rows))
    hdr = (
        f"{'Platform':<{name_w}}  {'Calls':>6}  {'Errs':>5}  {'Err%':>6}  "
        f"{'Med OK':>7}  {'HTTP FB':>7}  {'Retries':>9}  {'PhaseFail':>9}  {'Med Recov':>9}"
    )
    sep = "-" * len(hdr)
    print()
    print(hdr)
    print(sep)
    for r in rows:
        ret_str = f"{r['fired']}/{r['sched']}" if (r["fired"] or r["sched"]) else "0"
        print(
            f"{r['label']:<{name_w}}  {r['calls']:>6}  {r['errs']:>5}  {r['err_pct']:>6}  "
            f"{r['med_ms']:>7}  {r['fb']:>7}  {ret_str:>9}  {r['phase_n']:>9}  {r['med_rec']:>9}"
        )
    print(sep)
    tot_ret = f"{tot_row['fired']}/{tot_row['sched']}" if (tot_row["fired"] or tot_row["sched"]) else "0"
    print(
        f"{tot_row['label']:<{name_w}}  {tot_row['calls']:>6}  {tot_row['errs']:>5}  {tot_row['err_pct']:>6}  "
        f"{tot_row['med_ms']:>7}  {tot_row['fb']:>7}  {tot_ret:>9}  {tot_row['phase_n']:>9}  {tot_row['med_rec']:>9}"
    )


def fetch_desktop(since_dt, since_ms):
    db_stats = analyze_db(DESKTOP_DB, since_ms) if os.path.exists(DESKTOP_DB) else None
    text_stats = analyze_text_lines(read_desktop_console_lines(since_dt))
    return ("Desktop", db_stats, text_stats, "console logs")


def fetch_device(serial, since_ms):
    label = device_label(serial)
    with tempfile.TemporaryDirectory(prefix="om-stats-") as tmp:
        try:
            db_path, db_err = pull_device_db(serial, tmp)
            db_stats = analyze_db(db_path, since_ms) if db_path else None
        except Exception as e:  # keep the other devices' report; say why this one has no DB stats
            db_stats, db_err = None, f"{type(e).__name__}: {e}"
        logcat_lines = pull_device_logcat(serial)
        text_stats = analyze_text_lines(logcat_lines) if logcat_lines else None
        if db_err:
            label = f"{label} [no DB]"
            print(f"\n!! {label}: {db_err}", file=sys.stderr, flush=True)
        return (label, db_stats, text_stats, "logcat buffer")


def main():
    p = argparse.ArgumentParser(description="Open-Meteo timeout, fallback, phase, and retry stats.")
    p.add_argument("--days", type=float, default=7.0, help="Lookback window in days (default: 7)")
    p.add_argument("--since", help="Exact local start time: YYYY-MM-DD or YYYY-MM-DDTHH:MM")
    p.add_argument("--no-desktop", action="store_true", help="Skip desktop stats")
    p.add_argument("--no-devices", action="store_true", help="Skip adb-attached Android devices")
    p.add_argument("--serial", help="Only query a single adb device serial")
    p.add_argument("--episodes", type=int, default=10, help="Max recent episodes per platform (default: 10)")
    p.add_argument("--summary-only", action="store_true", help="Print only the summary table")
    args = p.parse_args()

    since_dt = parse_since(args)
    since_ms = int(since_dt.timestamp() * 1000)
    print(f"Open-Meteo stats since {since_dt.strftime('%Y-%m-%d %H:%M:%S')} (local)", flush=True)

    serials, not_ready = ([], []) if args.no_devices else connected_devices(args.serial)
    for serial, state in not_ready:
        print(f"!! adb lists {serial} as '{state}', skipped (unlock / accept the USB prompt, or reconnect)",
              file=sys.stderr, flush=True)
    results = []

    with ThreadPoolExecutor(max_workers=max(1, 1 + len(serials))) as pool:
        futures = []
        if not args.no_desktop:
            futures.append((0, pool.submit(fetch_desktop, since_dt, since_ms)))
        for idx, serial in enumerate(serials, start=1):
            futures.append((idx, pool.submit(fetch_device, serial, since_ms)))

        order_by_future = {fut: order for order, fut in futures}
        ordered_results = []
        for fut in as_completed(order_by_future):
            res = fut.result()
            ordered_results.append((order_by_future[fut], res))
            if not args.summary_only:
                label, db_stats, text_stats, text_label = res
                print_report(label, db_stats, text_stats, text_label, args.episodes)
                sys.stdout.flush()

        ordered_results.sort(key=lambda item: item[0])
        results = [res for _, res in ordered_results]

    if not args.summary_only and not args.no_devices and not serials:
        print("\n== Android devices\n   (no connected adb devices)")

    print_summary_table(results)


if __name__ == "__main__":
    main()
