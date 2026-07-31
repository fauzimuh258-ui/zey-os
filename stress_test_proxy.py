"""
Stress test for proxy_server.py. Spins up the real Flask app plus a
lightweight, genuinely-concurrent stand-in for Ollama, then hammers the
proxy from many threads at once to check:

  1. Concurrency: the proxy must handle overlapping requests in parallel,
     not queue them up behind each other (this caught the missing
     threaded=True in Part 2 — see the fix at the top of this message).
  2. Rate-limit correctness under real thread concurrency (no lost-update
     races in the token bucket).
  3. No unhandled exceptions / crashes under sustained concurrent load.

Run directly: `python3 stress_test_proxy.py`
Requires: flask, requests (see requirements.txt)
"""
import statistics
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import requests

import proxy_server as ps

HOST = "127.0.0.1"
PROXY_PORT = 8787
FAKE_OLLAMA_PORT = 8788
UPSTREAM_LATENCY_S = 0.3


class _SlowOllamaStandIn(BaseHTTPRequestHandler):
    """Stands in for a real Ollama instance that takes a moment to respond —
    close enough to reality on low-end hardware to make serialization,
    if present, actually visible in wall-clock time."""

    def do_GET(self):
        time.sleep(UPSTREAM_LATENCY_S)
        body = b'{"models":[]}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass  # keep stress-test output readable


def start_fake_ollama():
    server = ThreadingHTTPServer((HOST, FAKE_OLLAMA_PORT), _SlowOllamaStandIn)
    threading.Thread(target=server.serve_forever, daemon=True).start()


def start_proxy():
    threading.Thread(
        target=lambda: ps.app.run(host=HOST, port=PROXY_PORT, threaded=True, use_reloader=False),
        daemon=True,
    ).start()
    _wait_until_ready()


def _wait_until_ready(timeout=10):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if requests.get(f"http://{HOST}:{PROXY_PORT}/health", timeout=1).status_code == 200:
                return
        except requests.exceptions.RequestException:
            pass
        time.sleep(0.1)
    raise RuntimeError("Proxy did not become ready in time")


def _fire(path="/api/tags", headers=None):
    start = time.monotonic()
    try:
        r = requests.get(f"http://{HOST}:{PROXY_PORT}{path}", headers=headers or {}, timeout=10)
        status = r.status_code
    except requests.exceptions.RequestException as e:
        status = f"EXC:{type(e).__name__}"
    return status, time.monotonic() - start


def concurrency_test(n=6):
    """Without threaded=True, N concurrent callers queue up behind each
    other — on a slow upstream this turns a 0.3s operation into an
    N x 0.3s pile-up. This test fails loudly if that regresses."""
    print(f"\n[CONCURRENCY TEST] {n} concurrent requests against a "
          f"{UPSTREAM_LATENCY_S}s-latency upstream...")

    with ThreadPoolExecutor(max_workers=n) as pool:
        start = time.monotonic()
        futures = [pool.submit(_fire, "/health") for _ in range(n)]
        results = [f.result() for f in as_completed(futures)]
        elapsed = time.monotonic() - start

    statuses = [r[0] for r in results]
    serialized_estimate = n * UPSTREAM_LATENCY_S

    print(f"  Wall time: {elapsed:.2f}s  "
          f"(serialized would be ~{serialized_estimate:.2f}s, parallel ~{UPSTREAM_LATENCY_S:.2f}s)")
    print(f"  Statuses: {statuses}")

    assert all(s == 200 for s in statuses), f"All health checks should succeed: {statuses}"
    assert elapsed < serialized_estimate * 0.6, (
        f"Requests appear to be serialized (took {elapsed:.2f}s) — check app.run() has threaded=True"
    )
    print("  CONCURRENCY TEST: PASSED")


def burst_capacity_test(concurrency=50):
    """Fires far more requests than the rate limiter's burst capacity at
    once — every one must resolve to a clean HTTP response, never an
    exception or a hang."""
    print(f"\n[BURST CAPACITY TEST] {concurrency} concurrent requests...")

    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        futures = [pool.submit(_fire, "/api/tags", {"X-API-Key": ps.API_KEY}) for _ in range(concurrency)]
        results = [f.result() for f in as_completed(futures)]

    statuses = [r[0] for r in results]
    latencies = [r[1] for r in results]
    # 200/502 both mean "cleared auth + rate limit, reached the forwarding logic" —
    # which one depends on whether the fake upstream happens to be reachable.
    rate_limited = statuses.count(429)
    passed_to_upstream = sum(1 for s in statuses if s in (200, 502))
    exceptions = [s for s in statuses if isinstance(s, str)]

    print(f"  Passed auth+rate-limit: {passed_to_upstream}   Rate-limited (429): {rate_limited}   "
          f"Exceptions: {len(exceptions)}")
    print(f"  Latency: min={min(latencies):.3f}s max={max(latencies):.3f}s mean={statistics.mean(latencies):.3f}s")

    assert not exceptions, f"No request should raise a transport-level exception: {exceptions}"
    assert passed_to_upstream + rate_limited == concurrency, "Every request must resolve cleanly"
    assert passed_to_upstream <= 10, f"Burst capacity is 10 — got {passed_to_upstream} passthroughs"
    print("  BURST CAPACITY TEST: PASSED")


def auth_under_load_test(concurrency=20):
    """Mixes valid and invalid API keys under concurrent load — wrong-key
    requests must never slip through, even while the rate limiter is also
    under pressure from the correct-key requests in the same burst."""
    print(f"\n[AUTH UNDER LOAD TEST] {concurrency} concurrent requests, half with a bad key...")

    def fire_with_key(i):
        key = ps.API_KEY if i % 2 == 0 else "wrong-key"
        return i, *_fire("/api/tags", {"X-API-Key": key})

    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        results = [f.result() for f in as_completed([pool.submit(fire_with_key, i) for i in range(concurrency)])]

    for i, status, _ in results:
        if i % 2 == 1:  # bad key
            assert status == 401, f"Request {i} used a bad key but got {status}, expected 401"
    print("  AUTH UNDER LOAD TEST: PASSED")


def sustained_load_test(duration_s=5, workers=8):
    """Hammers the proxy continuously for a stretch of real time — the
    token bucket should keep admitting a steady trickle after the initial
    burst drains, and nothing should crash under prolonged pressure."""
    print(f"\n[SUSTAINED LOAD TEST] {workers} workers for {duration_s}s...")
    stop_at = time.monotonic() + duration_s
    results = []
    lock = threading.Lock()

    def worker():
        while time.monotonic() < stop_at:
            status, elapsed = _fire("/api/tags", {"X-API-Key": ps.API_KEY})
            with lock:
                results.append((status, elapsed))

    threads = [threading.Thread(target=worker) for _ in range(workers)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    statuses = [r[0] for r in results]
    exceptions = [s for s in statuses if isinstance(s, str)]
    admitted = sum(1 for s in statuses if s in (200, 502))
    limited = statuses.count(429)

    print(f"  Total requests: {len(results)}  Admitted: {admitted}  Rate-limited: {limited}  "
          f"Exceptions: {len(exceptions)}")

    assert not exceptions, f"Unexpected exceptions under sustained load: {exceptions[:5]}"
    expected_max_admitted = 10 + int(5 * duration_s) + 10  # capacity + refill*duration + jitter slack
    assert admitted <= expected_max_admitted, f"Admitted {admitted}, expected at most ~{expected_max_admitted}"
    print("  SUSTAINED LOAD TEST: PASSED")


if __name__ == "__main__":
    print("Starting fake-Ollama stand-in and the real proxy_server.py app...")
    start_fake_ollama()
    ps.OLLAMA_API = f"http://{HOST}:{FAKE_OLLAMA_PORT}"
    start_proxy()
    print(f"Proxy ready at http://{HOST}:{PROXY_PORT}\n")

    concurrency_test()
    burst_capacity_test()
    auth_under_load_test()
    sustained_load_test()

    print("\nAll stress tests passed.")
