from datetime import UTC, datetime

from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    Column,
    Date,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    MetaData,
    String,
    Table,
    UniqueConstraint,
    create_engine,
    event,
)
from sqlalchemy.dialects.mysql import DATETIME

metadata = MetaData()
ID = BigInteger().with_variant(Integer, "sqlite")
STAMP = DateTime().with_variant(DATETIME(fsp=6), "mysql")


def now():
    return datetime.now(UTC).replace(tzinfo=None)


def pk():
    return Column("id", ID, primary_key=True, autoincrement=True)


def stamp(name):
    return Column(name, STAMP, nullable=False, default=now)


users = Table(
    "users",
    metadata,
    pk(),
    Column("login_id", String(50), nullable=False, unique=True),
    Column("password", String(255), nullable=False),
    Column("name", String(30), nullable=False),
    Column("role", String(20), nullable=False),
    Column("status", String(20), nullable=False),
    Column("signup_note", String(500)),
    Column("auth_version", ID, nullable=False, server_default="0"),
    stamp("created_at"),
    stamp("updated_at"),
)
invites = Table(
    "invite_codes",
    metadata,
    pk(),
    Column("code", String(100), nullable=False, unique=True),
    Column("status", String(20), nullable=False),
    Column("expires_at", STAMP, nullable=False),
    Column("used_by_user_id", ID),
    Column("created_by", ID, nullable=False),
    stamp("created_at"),
    stamp("updated_at"),
)
schedule_lock = Table("villa_schedule_lock", metadata, Column("id", ID, primary_key=True))
audits = Table(
    "admin_audit",
    metadata,
    pk(),
    Column("actor_id", ID),
    Column("action", String(40), nullable=False),
    Column("target_id", ID),
    Column("reason", String(1000), nullable=False),
    stamp("created_at"),
)
resets = Table(
    "password_reset_tokens",
    metadata,
    pk(),
    Column("user_id", ID, ForeignKey("users.id"), nullable=False),
    Column("token_hash", String(64), nullable=False, unique=True),
    Column("expires_at", STAMP, nullable=False),
    Column("used_at", STAMP),
)
reservations = Table(
    "reservations",
    metadata,
    pk(),
    Column("user_id", ID, ForeignKey("users.id"), nullable=False),
    Column("start_date", Date, nullable=False),
    Column("end_date", Date, nullable=False),
    Column("guest_count", Integer, nullable=False),
    Column("memo", String(500), nullable=False),
    Column("status", String(20), nullable=False),
    Column("version", ID, nullable=False, server_default="0"),
    Column("policy_version", String(20), nullable=False),
    Column("request_key", String(100), nullable=False),
    Column("request_hash", String(64), nullable=False),
    stamp("created_at"),
    stamp("updated_at"),
    UniqueConstraint("user_id", "request_key", name="uq_reservation_request"),
    CheckConstraint("start_date <= end_date", name="ck_reservation_dates"),
    CheckConstraint("guest_count > 0", name="ck_guest_count"),
)
Index(
    "ix_reservation_user",
    reservations.c.user_id,
    reservations.c.status,
    reservations.c.start_date,
)
Index("ix_reservation_calendar", reservations.c.status, reservations.c.start_date)
blocks = Table(
    "calendar_blocks",
    metadata,
    pk(),
    Column("start_date", Date, nullable=False),
    Column("end_date", Date, nullable=False),
    Column("reason", String(500), nullable=False),
    Column("status", String(20), nullable=False),
    Column("version", ID, nullable=False, server_default="0"),
    Column("created_by", ID, ForeignKey("users.id"), nullable=False),
    stamp("created_at"),
    CheckConstraint("start_date <= end_date", name="ck_block_dates"),
)
occupancy = Table(
    "calendar_occupancy",
    metadata,
    Column("use_date", Date, primary_key=True),
    Column("reservation_id", ID, ForeignKey("reservations.id")),
    Column("block_id", ID, ForeignKey("calendar_blocks.id")),
    CheckConstraint(
        "(reservation_id IS NOT NULL AND block_id IS NULL) OR "
        "(reservation_id IS NULL AND block_id IS NOT NULL)",
        name="ck_occupancy_owner",
    ),
)
history = Table(
    "reservation_history",
    metadata,
    pk(),
    Column("reservation_id", ID, ForeignKey("reservations.id"), nullable=False),
    Column("actor_id", ID, ForeignKey("users.id"), nullable=False),
    Column("action", String(30), nullable=False),
    Column("reason", String(500), nullable=False),
    Column("before_value", String(1000)),
    Column("after_value", String(1000), nullable=False),
    stamp("created_at"),
)
Index("ix_history_reservation", history.c.reservation_id, history.c.created_at)
communications = Table(
    "communication_tasks",
    metadata,
    pk(),
    Column(
        "history_id",
        ID,
        ForeignKey("reservation_history.id"),
        nullable=False,
        unique=True,
    ),
    Column("recipient_id", ID, ForeignKey("users.id"), nullable=False),
    Column("status", String(20), nullable=False, server_default="PENDING"),
    Column("contacted_at", STAMP),
    Column("acknowledged_at", STAMP),
)
posts = Table(
    "board_posts",
    metadata,
    pk(),
    Column("user_id", ID, ForeignKey("users.id"), nullable=False),
    Column("title", String(100), nullable=False),
    Column("body", String(5000), nullable=False),
    Column("version", ID, nullable=False, server_default="0"),
    stamp("created_at"),
    stamp("updated_at"),
)
Index("ix_board_user", posts.c.user_id)
sessions = Table(
    "app_sessions",
    metadata,
    Column("token_hash", String(64), primary_key=True),
    Column("csrf", String(64), nullable=False),
    Column("user_id", ID, ForeignKey("users.id")),
    Column("auth_version", ID),
    Column("authenticated_at", STAMP),
    Column("expires_at", STAMP, nullable=False),
)
Index("ix_session_expiry", sessions.c.expires_at)


def make_engine(url):
    options = {"pool_pre_ping": True}
    if url.startswith("sqlite"):
        options["connect_args"] = {"check_same_thread": False}
    else:
        options.update(
            isolation_level="READ COMMITTED",
            pool_recycle=1800,
            connect_args={
                "connect_timeout": 5,
                "read_timeout": 10,
                "write_timeout": 10,
                "init_command": "SET time_zone = '+00:00'",
            },
        )
    engine = create_engine(url, **options)
    if engine.dialect.name == "sqlite":

        @event.listens_for(engine, "connect")
        def foreign_keys(connection, _):
            connection.execute("PRAGMA foreign_keys=ON")

    return engine
