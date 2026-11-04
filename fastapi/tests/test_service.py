import json
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta

import pytest
from conftest import Browser
from fastapi.testclient import TestClient
from sqlalchemy import select, update

from app.bookings import today
from app.db import audits, now, occupancy, sessions, users
from app.logging_config import JsonFormatter
from app.security import COOKIE, bootstrap, digest


def booking_payload(offset=2):
    day = (today() + timedelta(days=offset)).isoformat()
    return {"startDate": day, "endDate": day, "guestCount": 2, "memo": "가족 여행"}


def reserve(browser, offset=2, key="reservation-key-0001"):
    return browser.send(
        "POST", "/reservations", booking_payload(offset), headers={"Idempotency-Key": key}
    )


def test_health_templates_and_assets(application, browser):
    _, client = application
    assert client.get("/health").json() == {"status": "UP"}
    assert client.get("/health/ready").json() == {"status": "UP"}
    assert client.get("/", follow_redirects=False).headers["location"] == "/login"
    for mode in ("login", "signup", "reset"):
        page = browser.page("/" + mode)
        assert f'data-mode="{mode}"' in page.text
        assert "th:" not in page.text
        assert 'content="X-CSRF-TOKEN"' in page.text
    assert client.get("/assets/app.js").status_code == 200


def test_csrf_required_and_rotated(application, browser):
    _, client = application
    browser.page()
    old_token, old_cookie = browser.token, client.cookies[COOKIE]
    response = client.post(
        "/auth/login", json={"loginId": "admin", "password": "Administrator-123!"}
    )
    assert response.status_code == 403
    browser.login()
    assert old_cookie != client.cookies[COOKIE]
    assert old_token != browser.token
    response = client.post("/admin/invite-codes", headers={"X-CSRF-TOKEN": old_token})
    assert response.status_code == 403
    cookie = browser.send("POST", "/auth/logout").headers["set-cookie"]
    assert "Max-Age=0" in cookie
    assert client.get("/users/me").status_code == 401


def test_invite_single_use_pending_and_approval(application, admin):
    app, _ = application
    invite = admin.send("POST", "/admin/invite-codes").json()["code"]
    with TestClient(app) as client:
        member = Browser(client)
        member.page("/signup")
        body = {
            "loginId": "family",
            "password": "Family-password-123!",
            "name": "가족",
            "inviteCode": invite,
        }
        assert member.send("POST", "/auth/signup", body).status_code == 201
        assert (
            member.send(
                "POST", "/auth/login", {"loginId": "family", "password": body["password"]}
            ).status_code
            == 403
        )
        body["loginId"] = "other"
        assert member.send("POST", "/auth/signup", body).json()["code"] == "INVALID_INVITE"
    assert "code" not in admin.client.get("/admin/invite-codes").json()[0]


def test_member_permissions_and_invalidation(application, admin, member):
    person, user_id = member
    assert person.client.get("/admin/users").status_code == 403
    assert person.client.get("/manage").status_code == 403
    assert (
        admin.send(
            "PATCH",
            f"/admin/users/{user_id}/status",
            {"status": "SUSPENDED", "reason": "계정 정지"},
        ).status_code
        == 200
    )
    assert person.client.get("/users/me").status_code == 401


def test_last_admin_protected(admin):
    admin_id = admin.client.get("/users/me").json()["id"]
    for path, body in [
        ("role", {"role": "USER", "reason": "해제"}),
        ("status", {"status": "WITHDRAWN", "reason": "탈퇴"}),
    ]:
        assert (
            admin.send("PATCH", f"/admin/users/{admin_id}/{path}", body).json()["code"]
            == "LAST_ADMIN"
        )


def test_reservation_replay_conflict_and_privacy(admin, member):
    person, _ = member
    created = reserve(person)
    assert created.status_code == 201, created.text
    row = created.json()
    assert reserve(person).json()["id"] == row["id"]
    assert reserve(person, 3).json()["code"] == "IDEMPOTENCY_MISMATCH"
    assert reserve(admin).json()["code"] == "DATE_CONFLICT"
    calendar = person.client.get(
        f"/reservations/calendar?year={today().year}&month={today().month}"
    ).json()
    assert all("memo" not in item and "guestCount" not in item for item in calendar)
    assert len(person.client.get(f"/reservations/{row['id']}/history").json()) == 1
    assert person.send("DELETE", f"/reservations/{row['id']}").json()["status"] == "CANCELLED"
    assert reserve(admin).status_code == 201


def test_booking_policy_validation(application, admin):
    app, _ = application
    assert reserve(admin, 0).json()["code"] == "INVALID_DATES"
    body = booking_payload()
    body["guestCount"] = 9
    assert (
        admin.send(
            "POST", "/reservations", body, headers={"Idempotency-Key": "reservation-key-0001"}
        ).json()["code"]
        == "CAPACITY_EXCEEDED"
    )
    app.state.settings.max_guests = 0
    assert reserve(admin).json()["code"] == "CAPACITY_NOT_CONFIGURED"


def test_max_active_and_inclusive_dates(admin):
    body = booking_payload()
    body["endDate"] = (today() + timedelta(days=4)).isoformat()
    assert (
        admin.send(
            "POST", "/reservations", body, headers={"Idempotency-Key": "reservation-key-0001"}
        ).status_code
        == 201
    )
    assert reserve(admin, 4, "reservation-key-0002").json()["code"] == "DATE_CONFLICT"
    assert reserve(admin, 5, "reservation-key-0003").status_code == 201
    assert reserve(admin, 6, "reservation-key-0004").json()["code"] == "RESERVATION_LIMIT"


def test_admin_change_rollback_versions_and_notifications(application, admin, member):
    app, _ = application
    person, _ = member
    row = reserve(person).json()
    other = reserve(admin, 5).json()
    changed = {**booking_payload(5), "expectedVersion": 0, "reason": "일정 조정"}
    changed.pop("memo")
    assert (
        admin.send("PATCH", f"/admin/reservations/{row['id']}", changed).json()["code"]
        == "DATE_CONFLICT"
    )
    with app.state.engine.connect() as connection:
        occupied = list(connection.execute(select(occupancy.c.reservation_id)).scalars())
        assert sorted(occupied) == sorted([row["id"], other["id"]])
    changed.update(startDate=booking_payload(3)["startDate"], endDate=booking_payload(3)["endDate"])
    response = admin.send("PATCH", f"/admin/reservations/{row['id']}", changed)
    assert response.status_code == 200, response.text
    assert response.json()["version"] == 1
    assert (
        admin.send("PATCH", f"/admin/reservations/{row['id']}", changed).json()["code"]
        == "STALE_VERSION"
    )
    notice = person.client.get("/users/me/notices").json()[0]
    assert admin.send("PATCH", f"/admin/communications/{notice['id']}/contact").status_code == 200
    assert person.send("PATCH", f"/users/me/notices/{notice['id']}/acknowledge").status_code == 200
    assert person.client.get("/users/me/notices").json()[0]["status"] == "ACKNOWLEDGED"
    cancelled = admin.send(
        "PATCH", f"/admin/reservations/{row['id']}/cancel", {"expectedVersion": 1, "reason": "점검"}
    )
    assert cancelled.json()["status"] == "CANCELLED"


def test_block_release(admin):
    body = {
        "startDate": booking_payload()["startDate"],
        "endDate": booking_payload()["endDate"],
        "reason": "점검",
    }
    row = admin.send("POST", "/admin/calendar-blocks", body)
    assert row.status_code == 201, row.text
    block_id = row.json()["id"]
    assert reserve(admin).json()["code"] == "DATE_CONFLICT"
    assert (
        admin.send(
            "PATCH",
            f"/admin/calendar-blocks/{block_id}/release",
            {"expectedVersion": 1, "reason": "완료"},
        ).json()["code"]
        == "STALE_VERSION"
    )
    assert (
        admin.send(
            "PATCH",
            f"/admin/calendar-blocks/{block_id}/release",
            {"expectedVersion": 0, "reason": "완료"},
        ).status_code
        == 200
    )
    assert reserve(admin).status_code == 201


def test_board_crud_owner_permissions_versions_and_pagination(admin, member):
    person, _ = member
    post = person.send(
        "POST", "/board/posts", {"title": "가족 소식", "body": "안녕하세요 <script>"}
    ).json()
    assert admin.client.get(f"/board/posts/{post['id']}").json()["editable"] is False
    assert (
        admin.send(
            "PATCH",
            f"/board/posts/{post['id']}",
            {"title": "수정", "body": "내용", "expectedVersion": 0},
        ).status_code
        == 403
    )
    edited = person.send(
        "PATCH",
        f"/board/posts/{post['id']}",
        {"title": "수정", "body": "내용", "expectedVersion": 0},
    )
    assert edited.json()["version"] == 1
    assert (
        person.send("DELETE", f"/board/posts/{post['id']}?expectedVersion=0").json()["code"]
        == "STALE_VERSION"
    )
    assert admin.send("DELETE", f"/board/posts/{post['id']}?expectedVersion=1").status_code == 204
    for i in range(21):
        assert (
            person.send("POST", "/board/posts", {"title": f"소식 {i}", "body": "내용"}).status_code
            == 201
        )
    page = person.client.get("/board/posts").json()
    assert len(page["items"]) == 20 and page["hasNext"]
    next_page = person.client.get(f"/board/posts?before={page['items'][-1]['id']}").json()
    assert len(next_page["items"]) == 1 and not next_page["hasNext"]


def test_password_reset_one_time_and_session_revocation(admin, member):
    person, user_id = member
    token = admin.send(
        "POST", f"/admin/users/{user_id}/password-reset", {"reason": "본인 확인"}
    ).json()["token"]
    person.page("/reset")
    body = {"token": token, "password": "Changed-password-123!"}
    assert person.send("POST", "/auth/password/reset", body).status_code == 200
    assert person.client.get("/users/me").status_code == 401
    person.page("/reset")
    assert person.send("POST", "/auth/password/reset", body).json()["code"] == "INVALID_RESET_TOKEN"
    person.login("family", body["password"])
    assert (
        person.send(
            "POST",
            "/users/me/password",
            {"currentPassword": body["password"], "password": "Another-password-123!"},
        ).status_code
        == 200
    )
    assert person.client.get("/users/me").status_code == 401


def test_invalid_body_and_password_policy(admin, browser):
    assert admin.send("POST", "/board/posts", {"title": " ", "body": "내용"}).status_code == 400
    invite = admin.send("POST", "/admin/invite-codes").json()["code"]
    body = {"loginId": "family", "password": "짧은암호", "name": "가족", "inviteCode": invite}
    assert browser.send("POST", "/auth/signup", body).json()["code"] == "PASSWORD_POLICY"
    body["password"] = "가" * 25
    assert browser.send("POST", "/auth/signup", body).json()["code"] == "PASSWORD_POLICY"


def test_absolute_and_idle_session_expiry(application, admin):
    app, client = application
    token_hash = digest(client.cookies[COOKIE])
    with app.state.engine.begin() as connection:
        connection.execute(
            update(sessions)
            .where(sessions.c.token_hash == token_hash)
            .values(authenticated_at=now() - timedelta(hours=13))
        )
    assert client.get("/users/me").status_code == 401
    admin.login()
    with app.state.engine.begin() as connection:
        connection.execute(
            update(sessions)
            .where(sessions.c.token_hash == digest(client.cookies[COOKIE]))
            .values(expires_at=now() - timedelta(seconds=1))
        )
    assert client.get("/users/me").status_code == 401


def test_bootstrap_restart_preserves_data(application, admin):
    app, _ = application
    post = admin.send("POST", "/board/posts", {"title": "재시작", "body": "보존"}).json()
    with app.state.engine.begin() as connection:
        bootstrap(connection, app.state.settings)
        assert len(connection.execute(select(users.c.id)).all()) == 1
        assert (
            len(connection.execute(select(audits.c.id).where(audits.c.action == "BOOTSTRAP")).all())
            == 1
        )
    assert admin.client.get(f"/board/posts/{post['id']}").status_code == 200


def test_auth_rate_limit(application, browser):
    app, client = application
    browser.page()
    app.state.rate_windows["testclient"] = (0, 0)
    for _ in range(30):
        assert client.post("/auth/login", json={}).status_code in {400, 403}
    response = client.post("/auth/login", json={})
    assert response.status_code == 429
    assert response.headers["Retry-After"] == "60"


def test_headers_and_log_redaction(application, admin, caplog):
    import logging

    with caplog.at_level(logging.INFO, logger="villa.access"):
        response = admin.client.get("/users/me?password=private-secret")
    assert response.headers["x-content-type-options"] == "nosniff"
    assert response.headers["cache-control"] == "no-store"
    record = next(record for record in caplog.records if record.name == "villa.access")
    serialized = JsonFormatter().format(record)
    assert json.loads(serialized)["path"] == "/users/me"
    assert "private-secret" not in serialized


@pytest.mark.skipif(
    __import__("os").getenv("TEST_DATABASE_URL", "").startswith("mysql") is False,
    reason="Requires actual MySQL row locks",
)
def test_concurrent_bookings_have_one_winner(application, admin, member):
    app, _ = application
    person, _ = member

    def request(snapshot, key):
        with TestClient(app) as client:
            client.cookies.update(snapshot.client.cookies)
            return client.post(
                "/reservations",
                json=booking_payload(),
                headers={"X-CSRF-TOKEN": snapshot.token, "Idempotency-Key": key},
            ).status_code

    with ThreadPoolExecutor(max_workers=2) as executor:
        results = list(
            executor.map(
                lambda args: request(*args),
                [(admin, "concurrent-request-1"), (person, "concurrent-request-2")],
            )
        )
    assert sorted(results) == [201, 409]
