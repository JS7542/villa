import hashlib
import re
import secrets
from datetime import timedelta
from typing import Annotated

import bcrypt
from fastapi import Depends, Request, Response
from sqlalchemy import Connection, delete, insert, select, update

from .db import audits, now, schedule_lock, sessions, users

COOKIE = "VILLA_SESSION"
SAFE = {"GET", "HEAD", "OPTIONS"}


class DomainError(Exception):
    def __init__(self, status=400, code="INVALID_REQUEST", message="요청 내용을 확인해 주세요."):
        self.status, self.code, self.message = status, code, message


def fail(code, message, status=409):
    raise DomainError(status, code, message)


def missing():
    raise DomainError(404, "NOT_FOUND", "정보를 찾을 수 없습니다.")


def forbidden():
    raise DomainError(403, "FORBIDDEN", "권한이 없습니다.")


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def password_policy(value):
    if len(value) < 12 or len(value.encode()) > 72:
        fail(
            "PASSWORD_POLICY",
            "비밀번호는 12자 이상, UTF-8 기준 72바이트 이하여야 합니다.",
            400,
        )


def encode(value):
    password_policy(value)
    return bcrypt.hashpw(value.encode(), bcrypt.gensalt(rounds=12)).decode()


def matches(value, encoded):
    try:
        return len(value.encode()) <= 72 and bcrypt.checkpw(value.encode(), encoded.encode())
    except ValueError:
        return False


def normalize(value):
    value = value.strip().lower()
    if not re.fullmatch(r"[a-z0-9._-]{3,50}", value):
        fail(
            "LOGIN_ID_POLICY",
            "아이디는 영문 소문자·숫자·점·밑줄·하이픈 3~50자입니다.",
            400,
        )
    return value


def db(request: Request):
    # Function scope commits (or rolls back) before FastAPI sends the response.
    with request.app.state.engine.begin() as connection:
        yield connection


DB = Annotated[Connection, Depends(db, scope="function")]


def lock(connection):
    if (
        connection.execute(
            select(schedule_lock.c.id).where(schedule_lock.c.id == 1).with_for_update()
        ).scalar_one_or_none()
        != 1
    ):
        raise RuntimeError("MySQL init schema/seed is missing")


def audit(connection, actor, action, target, reason):
    connection.execute(
        insert(audits).values(actor_id=actor, action=action, target_id=target, reason=reason)
    )


def session(request: Request, connection):
    raw = request.cookies.get(COOKIE, "")
    if not raw or len(raw) > 100:
        return None
    return (
        connection.execute(
            select(sessions).where(
                sessions.c.token_hash == digest(raw), sessions.c.expires_at > now()
            )
        )
        .mappings()
        .first()
    )


def csrf(request: Request, connection):
    current = session(request, connection)
    if current is None or not secrets.compare_digest(
        request.headers.get("X-CSRF-TOKEN", ""), current["csrf"]
    ):
        fail(
            "FORBIDDEN",
            "요청 보안 토큰을 확인해 주세요. 페이지를 새로고침해 주세요.",
            403,
        )
    return current


def issue_session(request: Request, response: Response, connection, user=None):
    old = request.cookies.get(COOKIE)
    if old:
        connection.execute(delete(sessions).where(sessions.c.token_hash == digest(old)))
    connection.execute(delete(sessions).where(sessions.c.expires_at <= now()))
    raw, token = secrets.token_urlsafe(32), secrets.token_hex(32)
    connection.execute(
        insert(sessions).values(
            token_hash=digest(raw),
            csrf=token,
            user_id=user["id"] if user else None,
            auth_version=user["auth_version"] if user else None,
            authenticated_at=now() if user else None,
            expires_at=now() + timedelta(hours=1),
        )
    )
    response.set_cookie(
        COOKIE,
        raw,
        max_age=3600,
        httponly=True,
        secure=request.app.state.settings.cookie_secure,
        samesite="lax",
        path="/",
    )
    return token


def actor(request: Request, response: Response, connection: DB):
    if request.method not in SAFE:
        lock(connection)
        current = csrf(request, connection)
    else:
        current = session(request, connection)
    user = (
        connection.execute(select(users).where(users.c.id == current["user_id"])).mappings().first()
        if current
        else None
    )
    if (
        not user
        or user["status"] != "ACTIVE"
        or user["auth_version"] != current["auth_version"]
        or current["authenticated_at"] is None
        or current["authenticated_at"] + timedelta(hours=12) <= now()
    ):
        raise DomainError(401, "UNAUTHENTICATED", "다시 로그인해 주세요.")
    # Keep a sliding idle timeout without exceeding the absolute 12-hour lifetime.
    connection.execute(
        update(sessions)
        .where(sessions.c.token_hash == current["token_hash"])
        .values(
            expires_at=min(
                now() + timedelta(hours=1),
                current["authenticated_at"] + timedelta(hours=12),
            )
        )
    )
    response.set_cookie(
        COOKIE,
        request.cookies[COOKIE],
        max_age=3600,
        httponly=True,
        secure=request.app.state.settings.cookie_secure,
        samesite="lax",
        path="/",
    )
    return user


Actor = Annotated[dict, Depends(actor)]


def admin(user: Actor):
    if user["role"] != "ADMIN":
        forbidden()
    return user


Admin = Annotated[dict, Depends(admin)]


def revoke(connection, user_id):
    connection.execute(
        update(users)
        .where(users.c.id == user_id)
        .values(auth_version=users.c.auth_version + 1, updated_at=now())
    )
    connection.execute(delete(sessions).where(sessions.c.user_id == user_id))


def bootstrap(connection, settings):
    lock(connection)
    if not settings.bootstrap_enabled or connection.execute(select(users.c.id).limit(1)).first():
        return
    login, password = (
        normalize(settings.bootstrap_login),
        encode(settings.bootstrap_password),
    )
    user_id = connection.execute(
        insert(users).values(
            login_id=login,
            password=password,
            name="운영 관리자",
            role="ADMIN",
            status="ACTIVE",
        )
    ).inserted_primary_key[0]
    audit(connection, user_id, "BOOTSTRAP", user_id, "최초 관리자 생성")
