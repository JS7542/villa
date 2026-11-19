import calendar
import re
from datetime import date, datetime, timedelta, timezone

from fastapi import APIRouter, Header, Request
from sqlalchemy import delete, func, insert, select, update

from . import schemas as s
from .board import version
from .db import blocks, communications, history, now, occupancy, reservations, users
from .security import DB, Actor, Admin, audit, digest, fail, missing

router = APIRouter()
BOOKINGS = select(reservations, users.c.name.label("user_name")).join(
    users, users.c.id == reservations.c.user_id
)


def today():
    return datetime.now(timezone(timedelta(hours=9))).date()


def booking(row):
    return {
        "id": row["id"],
        "userId": row["user_id"],
        "userName": row["user_name"],
        "startDate": row["start_date"],
        "endDate": row["end_date"],
        "guestCount": row["guest_count"],
        "memo": row["memo"],
        "status": row["status"],
        "version": row["version"],
    }


def get(connection, booking_id):
    row = connection.execute(BOOKINGS.where(reservations.c.id == booking_id)).mappings().first()
    if not row:
        missing()
    return booking(row)


def visible(connection, user, booking_id):
    row = get(connection, booking_id)
    if user["role"] != "ADMIN" and row["userId"] != user["id"]:
        missing()
    return row


def validate_dates(settings, start, end, guests):
    if settings.max_guests <= 0:
        fail(
            "CAPACITY_NOT_CONFIGURED",
            "관리자가 실제 정원을 설정한 뒤 예약할 수 있습니다.",
        )
    if (
        end < start
        or start <= today()
        or end > today() + timedelta(days=settings.advance_days)
        or (end - start).days + 1 > settings.max_days
    ):
        fail(
            "INVALID_DATES",
            f"내일부터 예약 가능 기간 안에서 최대 {settings.max_days}일을 선택해 주세요.",
            400,
        )
    if not 1 <= guests <= settings.max_guests:
        fail("CAPACITY_EXCEEDED", f"이용 인원은 1~{settings.max_guests}명입니다.", 400)


def occupy(connection, start, end, reservation_id=None, block_id=None):
    occupied = connection.execute(
        select(occupancy.c.use_date).where(occupancy.c.use_date.between(start, end)).limit(1)
    ).first()
    if occupied:
        fail("DATE_CONFLICT", "다른 예약 또는 점검 기간과 겹칩니다.")
    connection.execute(
        insert(occupancy),
        [
            {
                "use_date": start + timedelta(days=i),
                "reservation_id": reservation_id,
                "block_id": block_id,
            }
            for i in range((end - start).days + 1)
        ],
    )


def state(row):
    return f"{row['startDate']} ~ {row['endDate']} / {row['guestCount']}명 / {row['status']} / v{row['version']}"


def record(connection, user, before, after, action, reason, notify=False):
    history_id = connection.execute(
        insert(history).values(
            reservation_id=after["id"],
            actor_id=user["id"],
            action=action,
            reason=reason,
            before_value=state(before) if before else None,
            after_value=state(after),
        )
    ).inserted_primary_key[0]
    if notify:
        connection.execute(
            insert(communications).values(history_id=history_id, recipient_id=after["userId"])
        )


@router.get("/reservations/policy")
def policy(request: Request, user: Actor):
    settings = request.app.state.settings
    return {
        "advanceDays": settings.advance_days,
        "maxDays": settings.max_days,
        "maxActive": settings.max_active,
        "maxGuests": settings.max_guests,
        "today": today(),
        "timezone": "Asia/Seoul",
        "dateMeaning": "시작일·종료일 모두 포함",
    }


@router.post("/reservations", status_code=201)
def create(
    data: s.BookingCreate,
    request: Request,
    connection: DB,
    user: Actor,
    idempotency_key: str = Header(alias="Idempotency-Key"),
):
    if not re.fullmatch(r"[a-zA-Z0-9_-]{16,100}", idempotency_key):
        fail("INVALID_REQUEST_KEY", "예약 요청 키가 올바르지 않습니다.", 400)
    memo = (data.memo or "").strip()
    request_hash = digest(f"{data.startDate}|{data.endDate}|{data.guestCount}|{memo}")
    replay = (
        connection.execute(
            BOOKINGS.where(
                reservations.c.user_id == user["id"],
                reservations.c.request_key == idempotency_key,
            )
        )
        .mappings()
        .first()
    )
    if replay:
        if replay["request_hash"] != request_hash:
            fail("IDEMPOTENCY_MISMATCH", "같은 요청 키로 다른 예약을 만들 수 없습니다.")
        return booking(replay)
    settings = request.app.state.settings
    validate_dates(settings, data.startDate, data.endDate, data.guestCount)
    active = connection.execute(
        select(func.count())
        .select_from(reservations)
        .where(
            reservations.c.user_id == user["id"],
            reservations.c.status == "CONFIRMED",
            reservations.c.end_date >= today(),
        )
    ).scalar_one()
    if active >= settings.max_active:
        fail(
            "RESERVATION_LIMIT",
            f"진행 중·미래 예약은 최대 {settings.max_active}건입니다.",
        )
    booking_id = connection.execute(
        insert(reservations).values(
            user_id=user["id"],
            start_date=data.startDate,
            end_date=data.endDate,
            guest_count=data.guestCount,
            memo=memo,
            status="CONFIRMED",
            policy_version="v1.1",
            request_key=idempotency_key,
            request_hash=request_hash,
        )
    ).inserted_primary_key[0]
    occupy(connection, data.startDate, data.endDate, reservation_id=booking_id)
    result = get(connection, booking_id)
    record(connection, user, None, result, "CREATE", "본인 예약")
    return result


@router.get("/reservations/me")
def mine(connection: DB, user: Actor):
    return [
        booking(row)
        for row in connection.execute(
            BOOKINGS.where(reservations.c.user_id == user["id"])
            .order_by(reservations.c.start_date.desc(), reservations.c.id.desc())
            .limit(500)
        ).mappings()
    ]


@router.get("/reservations/calendar")
def month(connection: DB, user: Actor, year: int, month: int):
    if not 2000 <= year <= 2100 or not 1 <= month <= 12:
        fail("INVALID_MONTH", "올바른 조회 월을 선택해 주세요.", 400)
    first, last = (
        date(year, month, 1),
        date(year, month, calendar.monthrange(year, month)[1]),
    )
    result = [
        {
            "id": row["id"],
            "kind": "RESERVATION",
            "userName": row["user_name"],
            "startDate": row["start_date"],
            "endDate": row["end_date"],
            "mine": row["user_id"] == user["id"],
        }
        for row in connection.execute(
            BOOKINGS.where(
                reservations.c.status == "CONFIRMED",
                reservations.c.start_date <= last,
                reservations.c.end_date >= first,
            ).order_by(reservations.c.start_date)
        ).mappings()
    ]
    result.extend(
        {
            "id": row["id"],
            "kind": "BLOCK",
            "userName": "점검·행사",
            "startDate": row["start_date"],
            "endDate": row["end_date"],
            "mine": False,
        }
        for row in connection.execute(
            select(blocks)
            .where(
                blocks.c.status == "ACTIVE",
                blocks.c.start_date <= last,
                blocks.c.end_date >= first,
            )
            .order_by(blocks.c.start_date)
        ).mappings()
    )
    return result


@router.get("/reservations/{booking_id}")
def detail(booking_id: int, connection: DB, user: Actor):
    return visible(connection, user, booking_id)


@router.get("/reservations/{booking_id}/history")
def changes(booking_id: int, connection: DB, user: Actor):
    visible(connection, user, booking_id)
    return [
        {
            "id": row["id"],
            "action": row["action"],
            "reason": row["reason"],
            "beforeValue": row["before_value"],
            "afterValue": row["after_value"],
            "createdAt": row["created_at"],
        }
        for row in connection.execute(
            select(history)
            .where(history.c.reservation_id == booking_id)
            .order_by(history.c.id.desc())
        ).mappings()
    ]


def cancel_internal(connection, user, before, reason, notify=False):
    connection.execute(delete(occupancy).where(occupancy.c.reservation_id == before["id"]))
    connection.execute(
        update(reservations)
        .where(reservations.c.id == before["id"])
        .values(status="CANCELLED", version=reservations.c.version + 1, updated_at=now())
    )
    after = get(connection, before["id"])
    record(
        connection,
        user,
        before,
        after,
        "FORCE_CANCEL" if notify else "CANCEL",
        reason,
        notify,
    )
    return after


@router.delete("/reservations/{booking_id}")
def cancel(booking_id: int, connection: DB, user: Actor):
    before = get(connection, booking_id)
    if before["userId"] != user["id"]:
        missing()
    if before["status"] == "CANCELLED":
        return before
    if before["startDate"] <= today():
        fail("CANCELLATION_CLOSED", "이용 시작일 이후에는 관리자에게 연락해 주세요.")
    return cancel_internal(connection, user, before, "본인 취소")


@router.get("/admin/reservations")
def all_bookings(connection: DB, user: Admin):
    return [
        booking(row)
        for row in connection.execute(
            BOOKINGS.order_by(reservations.c.start_date.desc(), reservations.c.id.desc()).limit(500)
        ).mappings()
    ]


@router.patch("/admin/reservations/{booking_id}")
def change(
    booking_id: int,
    data: s.BookingChange,
    request: Request,
    connection: DB,
    user: Admin,
):
    before = get(connection, booking_id)
    version(before, data.expectedVersion)
    if before["status"] != "CONFIRMED" or before["startDate"] <= today():
        fail("NOT_CHANGEABLE", "시작 전 확정 예약만 변경할 수 있습니다.")
    validate_dates(request.app.state.settings, data.startDate, data.endDate, data.guestCount)
    connection.execute(delete(occupancy).where(occupancy.c.reservation_id == booking_id))
    occupy(connection, data.startDate, data.endDate, reservation_id=booking_id)
    connection.execute(
        update(reservations)
        .where(reservations.c.id == booking_id)
        .values(
            start_date=data.startDate,
            end_date=data.endDate,
            guest_count=data.guestCount,
            version=reservations.c.version + 1,
            updated_at=now(),
        )
    )
    after = get(connection, booking_id)
    record(connection, user, before, after, "UPDATE", data.reason, True)
    return after


@router.patch("/admin/reservations/{booking_id}/cancel")
def force_cancel(booking_id: int, data: s.Cancel, connection: DB, user: Admin):
    before = get(connection, booking_id)
    version(before, data.expectedVersion)
    if before["status"] == "CANCELLED":
        return before
    if before["endDate"] < today():
        fail("PAST_RESERVATION", "종료된 이용 기록은 취소할 수 없습니다.")
    return cancel_internal(connection, user, before, data.reason, True)


def block_view(row):
    return {
        "id": row["id"],
        "startDate": row["start_date"],
        "endDate": row["end_date"],
        "reason": row["reason"],
        "status": row["status"],
        "version": row["version"],
    }


@router.get("/admin/calendar-blocks")
def list_blocks(connection: DB, user: Admin):
    return [
        block_view(row)
        for row in connection.execute(
            select(blocks).order_by(blocks.c.status, blocks.c.start_date.desc()).limit(500)
        ).mappings()
    ]


@router.post("/admin/calendar-blocks", status_code=201)
def block(data: s.BlockCreate, connection: DB, user: Admin):
    if (
        data.startDate < today()
        or data.endDate < data.startDate
        or data.endDate > today() + timedelta(days=365)
    ):
        fail("INVALID_DATES", "점검 기간은 오늘부터 1년 이내로 선택해 주세요.", 400)
    block_id = connection.execute(
        insert(blocks).values(
            start_date=data.startDate,
            end_date=data.endDate,
            reason=data.reason,
            status="ACTIVE",
            created_by=user["id"],
        )
    ).inserted_primary_key[0]
    occupy(connection, data.startDate, data.endDate, block_id=block_id)
    audit(connection, user["id"], "BLOCK_CREATE", block_id, data.reason)
    return {"id": block_id}


@router.patch("/admin/calendar-blocks/{block_id}/release")
def release(block_id: int, data: s.Cancel, connection: DB, user: Admin):
    row = connection.execute(select(blocks).where(blocks.c.id == block_id)).mappings().first()
    if not row:
        missing()
    version(row, data.expectedVersion)
    if row["status"] == "RELEASED":
        return
    connection.execute(delete(occupancy).where(occupancy.c.block_id == block_id))
    connection.execute(
        update(blocks)
        .where(blocks.c.id == block_id)
        .values(status="RELEASED", version=blocks.c.version + 1)
    )
    audit(connection, user["id"], "BLOCK_RELEASE", block_id, data.reason)


def notices(connection, user_id=None):
    query = (
        select(
            communications,
            history.c.reservation_id,
            history.c.action,
            history.c.reason,
            history.c.created_at,
            users.c.name,
        )
        .join(history, history.c.id == communications.c.history_id)
        .join(users, users.c.id == communications.c.recipient_id)
    )
    if user_id is not None:
        query = query.where(communications.c.recipient_id == user_id)
    return [
        {
            "id": row["id"],
            "reservationId": row["reservation_id"],
            "userName": row["name"],
            "action": row["action"],
            "reason": row["reason"],
            "status": row["status"],
            "createdAt": row["created_at"],
        }
        for row in connection.execute(query.order_by(communications.c.id).limit(200)).mappings()
    ]


@router.get("/users/me/notices")
def my_notices(connection: DB, user: Actor):
    return notices(connection, user["id"])


@router.get("/admin/communications")
def all_notices(connection: DB, user: Admin):
    return notices(connection)


@router.patch("/admin/communications/{notice_id}/contact")
def contact(notice_id: int, connection: DB, user: Admin):
    result = connection.execute(
        update(communications)
        .where(communications.c.id == notice_id, communications.c.status == "PENDING")
        .values(status="CONTACTED", contacted_at=now())
    )
    if result.rowcount:
        audit(connection, user["id"], "CONTACTED", notice_id, "예약자에게 직접 연락 완료")


@router.patch("/users/me/notices/{notice_id}/acknowledge")
def acknowledge(notice_id: int, connection: DB, user: Actor):
    if not connection.execute(
        select(communications.c.id).where(
            communications.c.id == notice_id,
            communications.c.recipient_id == user["id"],
        )
    ).first():
        missing()
    connection.execute(
        update(communications)
        .where(communications.c.id == notice_id)
        .values(status="ACKNOWLEDGED", acknowledged_at=now())
    )
