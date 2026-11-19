import secrets
from datetime import timedelta

from fastapi import APIRouter, Request, Response
from sqlalchemy import delete, func, insert, select, update

from . import schemas as s
from .db import audits, invites, now, resets, sessions, users
from .security import (
    COOKIE,
    DB,
    Actor,
    Admin,
    DomainError,
    audit,
    csrf,
    digest,
    encode,
    fail,
    issue_session,
    lock,
    matches,
    missing,
    normalize,
    revoke,
)

router = APIRouter()


def user_view(user):
    return {
        "id": user["id"],
        "loginId": user["login_id"],
        "name": user["name"],
        "role": user["role"],
        "status": user["status"],
        "signupNote": user["signup_note"],
    }


@router.post("/auth/login")
def login(data: s.Login, request: Request, response: Response, connection: DB):
    csrf(request, connection)
    user = (
        connection.execute(select(users).where(users.c.login_id == data.loginId.strip().lower()))
        .mappings()
        .first()
    )
    # A fixed bcrypt hash gives unknown accounts a password check too.
    encoded = (
        user["password"] if user else "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
    )
    valid = matches(data.password, encoded)
    if not user or not valid:
        raise DomainError(401, "BAD_CREDENTIALS", "아이디 또는 비밀번호가 올바르지 않습니다.")
    lock(connection)
    user = connection.execute(select(users).where(users.c.id == user["id"])).mappings().one()
    if user["status"] != "ACTIVE" or user["password"] != encoded:
        fail("ACCOUNT_INACTIVE", "관리자 승인 또는 계정 상태 확인이 필요합니다.", 403)
    issue_session(request, response, connection, user)
    return user_view(user)


@router.post("/auth/signup", status_code=201)
def signup(data: s.Signup, request: Request, connection: DB):
    csrf(request, connection)
    login_id, password = normalize(data.loginId), encode(data.password)
    lock(connection)
    if connection.execute(select(users.c.id).where(users.c.login_id == login_id)).first():
        fail("LOGIN_ID_TAKEN", "사용할 수 없는 아이디입니다.")
    invite = (
        connection.execute(select(invites).where(invites.c.code == digest(data.inviteCode.strip())))
        .mappings()
        .first()
    )
    if not invite or invite["status"] != "ACTIVE" or invite["expires_at"] <= now():
        fail("INVALID_INVITE", "유효하지 않은 초대코드입니다.", 400)
    user_id = connection.execute(
        insert(users).values(
            login_id=login_id,
            password=password,
            name=data.name,
            role="USER",
            status="PENDING",
            signup_note=data.signupNote,
        )
    ).inserted_primary_key[0]
    connection.execute(
        update(invites)
        .where(invites.c.id == invite["id"])
        .values(status="USED", used_by_user_id=user_id, updated_at=now())
    )


@router.post("/auth/logout", status_code=204)
def logout(request: Request, response: Response, connection: DB, user: Actor):
    connection.execute(
        delete(sessions).where(sessions.c.token_hash == digest(request.cookies[COOKIE]))
    )
    response.delete_cookie(
        COOKIE,
        path="/",
        secure=request.app.state.settings.cookie_secure,
        httponly=True,
        samesite="lax",
    )


@router.get("/users/me")
def me(user: Actor):
    return user_view(user)


@router.get("/admin/users")
def all_users(connection: DB, user: Admin):
    return [
        user_view(row)
        for row in connection.execute(
            select(users).order_by(users.c.id.desc()).limit(500)
        ).mappings()
    ]


@router.get("/admin/users/pending")
def pending(connection: DB, user: Admin):
    return [
        user_view(row)
        for row in connection.execute(
            select(users).where(users.c.status == "PENDING").order_by(users.c.id)
        ).mappings()
    ]


@router.get("/admin/audit")
def audit_log(connection: DB, user: Admin):
    return [
        dict(row)
        for row in connection.execute(
            select(audits).order_by(audits.c.id.desc()).limit(200)
        ).mappings()
    ]


def target_user(connection, user_id):
    target = connection.execute(select(users).where(users.c.id == user_id)).mappings().first()
    if not target:
        missing()
    return target


def protect_admin(connection, target):
    count = connection.execute(
        select(func.count())
        .select_from(users)
        .where(users.c.role == "ADMIN", users.c.status == "ACTIVE")
    ).scalar_one()
    if target["role"] == "ADMIN" and target["status"] == "ACTIVE" and count <= 1:
        fail("LAST_ADMIN", "마지막 관리자를 비활성화하거나 권한을 해제할 수 없습니다.")


@router.patch("/admin/users/{user_id}/status")
def change_status(user_id: int, data: s.UserStatus, connection: DB, user: Admin):
    target = target_user(connection, user_id)
    before = target["status"]
    if before == data.status:
        return
    allowed = {
        "PENDING": {"ACTIVE", "REJECTED"},
        "ACTIVE": {"SUSPENDED", "WITHDRAWN"},
        "SUSPENDED": {"ACTIVE", "WITHDRAWN"},
    }
    if data.status not in allowed.get(before, set()):
        fail("INVALID_STATUS_TRANSITION", "허용하지 않는 계정 상태 변경입니다.")
    protect_admin(connection, target)
    connection.execute(update(users).where(users.c.id == user_id).values(status=data.status))
    revoke(connection, user_id)
    audit(
        connection,
        user["id"],
        "USER_STATUS",
        user_id,
        f"{before} → {data.status}: {data.reason}",
    )


@router.patch("/admin/users/{user_id}/role")
def change_role(user_id: int, data: s.UserRole, connection: DB, user: Admin):
    target = target_user(connection, user_id)
    if target["status"] != "ACTIVE":
        fail("INACTIVE_USER", "활성 계정만 권한을 변경할 수 있습니다.")
    if target["role"] == data.role:
        return
    protect_admin(connection, target)
    connection.execute(update(users).where(users.c.id == user_id).values(role=data.role))
    revoke(connection, user_id)
    audit(
        connection,
        user["id"],
        "USER_ROLE",
        user_id,
        f"{target['role']} → {data.role}: {data.reason}",
    )


@router.post("/admin/invite-codes", status_code=201)
def issue_invite(connection: DB, user: Admin):
    raw, expiry = secrets.token_urlsafe(32), now() + timedelta(days=7)
    invite_id = connection.execute(
        insert(invites).values(
            code=digest(raw), status="ACTIVE", expires_at=expiry, created_by=user["id"]
        )
    ).inserted_primary_key[0]
    audit(connection, user["id"], "INVITE_CREATE", invite_id, "7일 유효 초대 발급")
    return {"id": invite_id, "code": raw, "expiresAt": expiry}


@router.get("/admin/invite-codes")
def list_invites(connection: DB, user: Admin):
    return [
        {
            "id": row["id"],
            "status": "EXPIRED"
            if row["status"] == "ACTIVE" and row["expires_at"] <= now()
            else row["status"],
            "expiresAt": row["expires_at"],
            "usedByUserId": row["used_by_user_id"],
        }
        for row in connection.execute(
            select(invites).order_by(invites.c.id.desc()).limit(200)
        ).mappings()
    ]


@router.patch("/admin/invite-codes/{invite_id}/revoke")
def revoke_invite(invite_id: int, connection: DB, user: Admin):
    invite = connection.execute(select(invites).where(invites.c.id == invite_id)).mappings().first()
    if not invite:
        missing()
    if invite["status"] == "USED":
        fail("INVITE_USED", "이미 사용한 초대입니다.")
    if invite["status"] != "REVOKED":
        connection.execute(
            update(invites)
            .where(invites.c.id == invite_id)
            .values(status="REVOKED", updated_at=now())
        )
        audit(connection, user["id"], "INVITE_REVOKE", invite_id, "초대 폐기")


@router.post("/admin/users/{user_id}/password-reset")
def issue_reset(user_id: int, data: s.Reason, connection: DB, user: Admin):
    if target_user(connection, user_id)["status"] != "ACTIVE":
        fail("ACCOUNT_INACTIVE", "활성 계정만 재설정할 수 있습니다.")
    connection.execute(delete(resets).where(resets.c.user_id == user_id))
    raw = secrets.token_urlsafe(32)
    connection.execute(
        insert(resets).values(
            user_id=user_id,
            token_hash=digest(raw),
            expires_at=now() + timedelta(minutes=15),
        )
    )
    audit(connection, user["id"], "RESET_ISSUE", user_id, data.reason)
    return {"token": raw}


@router.post("/auth/password/reset")
def reset_password(data: s.Reset, request: Request, connection: DB):
    csrf(request, connection)
    password = encode(data.password)
    lock(connection)
    token = (
        connection.execute(
            select(resets).where(
                resets.c.token_hash == digest(data.token),
                resets.c.used_at.is_(None),
                resets.c.expires_at > now(),
            )
        )
        .mappings()
        .first()
    )
    if not token:
        fail("INVALID_RESET_TOKEN", "재설정 코드가 유효하지 않거나 만료되었습니다.", 400)
    target = target_user(connection, token["user_id"])
    if target["status"] != "ACTIVE":
        fail("ACCOUNT_INACTIVE", "활성 계정만 재설정할 수 있습니다.", 403)
    connection.execute(update(users).where(users.c.id == target["id"]).values(password=password))
    revoke(connection, target["id"])
    connection.execute(
        update(resets)
        .where(resets.c.user_id == target["id"], resets.c.used_at.is_(None))
        .values(used_at=now())
    )
    audit(
        connection,
        target["id"],
        "PASSWORD_RESET",
        target["id"],
        "본인 비밀번호 재설정 완료",
    )


@router.post("/users/me/password")
def change_password(data: s.PasswordChange, connection: DB, user: Actor):
    password = encode(data.password)
    if not matches(data.currentPassword, user["password"]):
        fail("BAD_PASSWORD", "현재 비밀번호가 올바르지 않습니다.", 400)
    connection.execute(update(users).where(users.c.id == user["id"]).values(password=password))
    revoke(connection, user["id"])
    connection.execute(delete(resets).where(resets.c.user_id == user["id"]))
    audit(connection, user["id"], "PASSWORD_CHANGE", user["id"], "본인 비밀번호 변경")
