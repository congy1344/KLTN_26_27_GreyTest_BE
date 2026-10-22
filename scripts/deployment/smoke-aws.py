"""Kiểm tra HTTP qua proxy sau deploy; chỉ dùng thư viện chuẩn Python."""

import json
import sys
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def request(base_url, path):
    try:
        response = urlopen(Request(base_url + path, headers={"Accept": "application/json"}), timeout=20)
    except HTTPError as error:
        response = error
    with response:
        return response.status, response.headers, response.read().decode("utf-8")


def main():
    base_url = sys.argv[1].rstrip("/") if len(sys.argv) == 2 else "http://127.0.0.1:18080"
    status, headers, body = request(base_url, "/api/ping")
    assert status == 200 and json.loads(body)["status"] == "ok", "Backend/proxy ping failed"
    assert headers.get("X-Content-Type-Options") == "nosniff", "Missing proxy response header"

    status, _, body = request(base_url, "/projects/deployment-smoke")
    assert status == 200 and "<html" in body.lower(), "SPA deep-link fallback failed"

    status, headers, body = request(base_url, "/api/auth/me")
    assert 400 <= status < 500 and "application/json" in headers.get("Content-Type", ""), "API error became SPA HTML"
    json.loads(body)

    for path in ("/api/payment", "/api/payment/vnpay-callback", "/api/payment;x/vnpay-callback"):
        status, _, body = request(base_url, path)
        assert status == 503 and json.loads(body)["code"] == "PAYMENT_DISABLED", "Payment route was not blocked"
    print("PASS: proxy ping, SPA deep link, API JSON error, disabled payment")


if __name__ == "__main__":
    main()
