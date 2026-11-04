import os
from dataclasses import dataclass, field

from sqlalchemy import URL


@dataclass
class Settings:
    database_url: str = field(
        default_factory=lambda: (
            os.getenv("DATABASE_URL", "")
            or str(
                URL.create(
                    "mysql+pymysql",
                    username=os.getenv("MYSQL_USER", "villa"),
                    password=os.getenv("MYSQL_PASSWORD", ""),
                    host=os.getenv("MYSQL_HOST", "mysql"),
                    port=3306,
                    database=os.getenv("MYSQL_DATABASE", "villa"),
                    query={"charset": "utf8mb4"},
                ).render_as_string(hide_password=False)
            )
        )
    )
    cookie_secure: bool = field(
        default_factory=lambda: os.getenv("COOKIE_SECURE", "false").lower() == "true"
    )
    max_guests: int = field(default_factory=lambda: int(os.getenv("VILLA_MAX_GUESTS", "0")))
    advance_days: int = 90
    max_days: int = 3
    max_active: int = 2
    bootstrap_enabled: bool = field(
        default_factory=lambda: os.getenv("BOOTSTRAP_ENABLED", "false").lower() == "true"
    )
    bootstrap_login: str = field(default_factory=lambda: os.getenv("BOOTSTRAP_LOGIN_ID", ""))
    bootstrap_password: str = field(default_factory=lambda: os.getenv("BOOTSTRAP_PASSWORD", ""))
