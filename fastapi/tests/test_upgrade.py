"""Verify V5 MySQL data can be retained with only the session-table upgrade."""

import os
from pathlib import Path

import bcrypt
import pytest
from conftest import Browser
from sqlalchemy import insert

from app.db import metadata, now, posts, users


@pytest.mark.skipif(
    not os.getenv("TEST_DATABASE_URL", "").startswith("mysql"), reason="MySQL migration SQL"
)
def test_existing_spring_mysql_data(application):
    app, client = application
    engine = app.state.engine
    metadata.drop_all(engine)
    root = Path(__file__).resolve().parents[2]
    migration_dir = root / "legacy/spring/src/main/resources/db/migration"
    with engine.begin() as connection:
        for version in (
            "V1__legacy_schema.sql",
            "V2__service_foundation.sql",
            "V4__reservations.sql",
            "V5__family_board.sql",
        ):
            for statement in (migration_dir / version).read_text(encoding="utf-8").split(";"):
                if statement.strip():
                    connection.exec_driver_sql(statement)
        for statement in (
            (root / "mysql/upgrade/001-fastapi-sessions.sql").read_text(encoding="utf-8").split(";")
        ):
            if statement.strip():
                connection.exec_driver_sql(statement)
        user_id = connection.execute(
            insert(users).values(
                login_id="oldadmin",
                name="이전 관리자",
                role="ADMIN",
                status="ACTIVE",
                password=bcrypt.hashpw(
                    b"Previous-password-123!", bcrypt.gensalt(prefix=b"2a")
                ).decode(),
                created_at=now(),
                updated_at=now(),
            )
        ).inserted_primary_key[0]
        post_id = connection.execute(
            insert(posts).values(user_id=user_id, title="기존 게시글", body="기존 내용")
        ).inserted_primary_key[0]
    browser = Browser(client)
    browser.login("oldadmin", "Previous-password-123!")
    assert client.get(f"/board/posts/{post_id}").json()["body"] == "기존 내용"
    assert browser.send("POST", "/admin/invite-codes").status_code == 201
