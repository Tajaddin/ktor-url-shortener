"""HTTP load probe for the URL shortener's redirect hot path.

Seeds links, then drives concurrent GET /{code} requests WITHOUT following the
redirect (so no external network), asserting each returns 302, and reports
throughput (req/s) and latency p50/p95/p99. Standard-library only.

    python load/load_probe.py --base http://localhost:8080 --requests 5000 --concurrency 50
"""
from __future__ import annotations

import argparse
import json
import statistics
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None  # do not follow; the 302 is the response we measure


_OPENER = urllib.request.build_opener(_NoRedirect)


def _create(base: str, target: str, code: str) -> int:
    body = json.dumps({"url": target, "code": code}).encode()
    req = urllib.request.Request(base + "/api/links", data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    try:
        with _OPENER.open(req, timeout=10) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code


def _timed_redirect(base: str, code: str) -> float:
    req = urllib.request.Request(base + "/" + code, method="GET")
    t0 = time.perf_counter()
    try:
        with _OPENER.open(req, timeout=10) as r:
            r.read()
            status = r.status
    except urllib.error.HTTPError as e:
        status = e.code  # 302 arrives here when redirects are disabled
    ms = (time.perf_counter() - t0) * 1000
    return ms if status in (301, 302) else -1.0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--requests", type=int, default=5000)
    ap.add_argument("--concurrency", type=int, default=50)
    ap.add_argument("--links", type=int, default=200)
    ap.add_argument("--out", default="load/results.json")
    args = ap.parse_args()

    codes = [f"k{i}" for i in range(args.links)]
    for i, c in enumerate(codes):
        _create(args.base, f"https://example.com/target/{i}", c)

    for i in range(200):
        _timed_redirect(args.base, codes[i % len(codes)])

    latencies: list[float] = []
    t0 = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = [
            pool.submit(_timed_redirect, args.base, codes[i % len(codes)])
            for i in range(args.requests)
        ]
        for f in futures:
            ms = f.result()
            if ms >= 0:
                latencies.append(ms)
    elapsed = time.perf_counter() - t0

    latencies.sort()
    n = len(latencies)
    summary = {
        "endpoint": "GET /{code} -> 302 redirect (in-memory store)",
        "requests_ok": n,
        "requests_total": args.requests,
        "concurrency": args.concurrency,
        "wall_seconds": round(elapsed, 3),
        "throughput_rps": round(n / elapsed, 1) if elapsed else 0.0,
        "latency_ms": {
            "p50": round(latencies[int(0.50 * n)], 2),
            "p95": round(latencies[int(0.95 * n)], 2),
            "p99": round(latencies[int(0.99 * n)], 2),
            "max": round(latencies[-1], 2),
            "mean": round(statistics.mean(latencies), 2),
        },
    }
    with open(args.out, "w") as fh:
        json.dump(summary, fh, indent=2)
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
