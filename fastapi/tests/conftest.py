import os
import re
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import insert

from app.config import Settings
from app.db import make_engine, metadata, schedule_lock
from app.main import create_app


@pytest.fixture
def application(tmp_path):
    url = os.getenv("TEST_DATABASE_URL", "sqlite:///" + (tmp_path / "villa.db").as_posix())
    engine = make_engine(url)
    if engine.dialect.name != "sqlite":
        # Never clear an application/production database by accident.
        assert engine.url.database.endswith("_test"), "Use a dedicated *_test database"
        metadata.drop_all(engine)
        sql = Path(__file__).resolve().parents[2] / "mysql/init/001-schema.sql"
        script = "\n".join(
            line
            for line in sql.read_text(encoding="utf-8").splitlines()
            if not line.lstrip().startswith("--")
        )
        with engine.begin() as connection:
            for statement in script.split(";"):
                if statement.strip():
                    connection.exec_driver_sql(statement)
    else:
        metadata.create_all(engine)
        with engine.begin() as connection:
            connection.execute(insert(schedule_lock).values(id=1))
    settings = Settings(
        database_url=url,
        cookie_secure=False,
        max_guests=8,
        bootstrap_enabled=True,
        bootstrap_login="admin",
        bootstrap_password="Administrator-123!",
    )
    app = create_app(settings, engine)
    with TestClient(app) as client:
        yield app, client
    engine.dispose()


class Browser:
    def __init__(self, client):
        self.client = client
        self.token = None

    def page(self, path="/login"):
        response = self.client.get(path)
        assert response.status_code == 200, response.text
        self.token = re.search(r'name="_csrf" content="([^"]+)"', response.text)[1]
        return response

    def send(self, method, path, body=None, **kwargs):
        headers = {"X-CSRF-TOKEN": self.token, **kwargs.pop("headers", {})}
        return self.client.request(method, path, json=body, headers=headers, **kwargs)

    def login(self, name="admin", password="Administrator-123!"):
        self.page()
        response = self.send("POST", "/auth/login", {"loginId": name, "password": password})
        assert response.status_code == 200, response.text
        self.page("/")
        return response.json()


@pytest.fixture
def browser(application):
    app, client = application
    return Browser(client)


@pytest.fixture
def admin(browser):
    browser.login()
    return browser


@pytest.fixture
def member(application, admin):
    app, _ = application
    invite = admin.send("POST", "/admin/invite-codes").json()["code"]
    with TestClient(app) as client:
        member = Browser(client)
        member.page("/signup")
        response = member.send(
            "POST",
            "/auth/signup",
            {
                "loginId": "family",
                "password": "Family-password-123!",
                "name": "가족",
                "inviteCode": invite,
                "signupNote": "테스트 가족",
            },
        )
        assert response.status_code == 201, response.text
        user_id = admin.client.get("/admin/users/pending").json()[0]["id"]
        assert (
            admin.send(
                "PATCH",
                f"/admin/users/{user_id}/status",
                {"status": "ACTIVE", "reason": "가입 승인"},
            ).status_code
            == 200
        )
        member.login("family", "Family-password-123!")
        yield member, user_id
