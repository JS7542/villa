"""Exercise the deployed HTTP stack. Uses only Python's standard library."""
import http.cookiejar
import json
import os
import re
import urllib.request
import uuid
from datetime import date, timedelta

base = os.getenv("TEST_BASE_URL", "http://127.0.0.1:8080")
cookies = http.cookiejar.CookieJar()
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))


def request(path, method="GET", body=None, token=None, **headers):
    if token:
        headers["X-CSRF-TOKEN"] = token
    if body is not None:
        headers["Content-Type"] = "application/json"
    payload = json.dumps(body).encode() if body is not None else None
    with client.open(urllib.request.Request(base + path, data=payload, method=method, headers=headers), timeout=15) as response:
        raw = response.read().decode()
        return json.loads(raw) if "application/json" in response.headers.get("Content-Type", "") and raw else raw


assert request("/health/ready")["status"] == "UP"
token = re.search(r'name="_csrf" content="([^"]+)"', request("/login"))[1]
request("/auth/login", "POST", {"loginId": os.getenv("BOOTSTRAP_LOGIN_ID", "admin"),
    "password": os.environ["BOOTSTRAP_PASSWORD"]}, token)
token = re.search(r'name="_csrf" content="([^"]+)"', request("/"))[1]
for page in ("/manage", "/board"):
    assert "th:" not in request(page)
policy = request("/reservations/policy")
day = (date.fromisoformat(policy["today"]) + timedelta(days=2)).isoformat()
reservation = request("/reservations", "POST", {"startDate": day, "endDate": day, "guestCount": 1,
    "memo": "Compose smoke"}, token, **{"Idempotency-Key": uuid.uuid4().hex})
assert request(f"/reservations/{reservation['id']}")["status"] == "CONFIRMED"
assert request(f"/reservations/{reservation['id']}", "DELETE", token=token)["status"] == "CANCELLED"
post = request("/board/posts", "POST", {"title": "Compose smoke", "body": "서비스 연결과 데이터 저장 확인"}, token)
assert request(f"/board/posts/{post['id']}")["body"] == post["body"]
print(f"Smoke passed: login, templates, reservation, board; persisted post id={post['id']}")
