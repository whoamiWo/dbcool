#!/usr/bin/env python3
"""
PHASE131 P95 诊断：轻量并发压测器（无 wrk/k6 依赖）。

用法：
    python3 scripts/perf-load.py --url http://localhost:8080/api/health \
        --concurrency 50 --duration 30 [--token <JWT>] [--method GET]

输出：请求总数、吞吐、平均/P50/P95/P99 延迟（毫秒）。
"""
import argparse
import json
import statistics
import threading
import time
import urllib.error
import urllib.request


def worker(url, method, token, deadline, latencies, errors, counter, lock):
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    while time.time() < deadline:
        start = time.perf_counter()
        try:
            req = urllib.request.Request(url, method=method, headers=headers)
            with urllib.request.urlopen(req, timeout=10) as resp:
                resp.read()
            elapsed = (time.perf_counter() - start) * 1000
            with lock:
                latencies.append(elapsed)
                counter[0] += 1
        except Exception as exc:  # noqa: BLE001 - 压测需吞掉所有网络异常
            with lock:
                errors.append(repr(exc))


def percentile(sorted_vals, pct):
    if not sorted_vals:
        return 0.0
    idx = min(len(sorted_vals) - 1, int(len(sorted_vals) * pct))
    return sorted_vals[idx]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://localhost:8080/api/health")
    ap.add_argument("--method", default="GET")
    ap.add_argument("--concurrency", type=int, default=50)
    ap.add_argument("--duration", type=int, default=30)
    ap.add_argument("--token", default=None)
    args = ap.parse_args()

    latencies, errors, counter = [], [], [0]
    lock = threading.Lock()
    deadline = time.time() + args.duration

    threads = [
        threading.Thread(
            target=worker,
            args=(args.url, args.method, args.token, deadline, latencies, errors, counter, lock),
            daemon=True,
        )
        for _ in range(args.concurrency)
    ]
    t0 = time.time()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    wall = time.time() - t0

    latencies.sort()
    total = len(latencies)
    result = {
        "url": args.url,
        "concurrency": args.concurrency,
        "duration_s": round(wall, 2),
        "requests": total,
        "errors": len(errors),
        "throughput_rps": round(total / wall, 1) if wall else 0,
        "avg_ms": round(statistics.mean(latencies), 1) if latencies else 0,
        "p50_ms": round(percentile(latencies, 0.50), 1),
        "p95_ms": round(percentile(latencies, 0.95), 1),
        "p99_ms": round(percentile(latencies, 0.99), 1),
        "max_ms": round(latencies[-1], 1) if latencies else 0,
    }
    print(json.dumps(result, indent=2, ensure_ascii=False))
    if errors:
        print("\n前 3 个错误样本:")
        for e in errors[:3]:
            print(" -", e)


if __name__ == "__main__":
    main()
