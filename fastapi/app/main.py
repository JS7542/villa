import logging
import time
import uuid
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from sqlalchemy import select
from sqlalchemy.exc import DBAPIError, IntegrityError

from . import accounts, board, bookings
from .config import Settings
from .db import make_engine, schedule_lock
from .security import DB, DomainError, actor, bootstrap, issue_session, session

ROOT = Path(__file__).resolve().parent
logger = logging.getLogger("villa")
access = logging.getLogger("villa.access")
templates = Jinja2Templates(directory=ROOT / "templates")


def error(status, code, message, trace_id, fields=None):
    return JSONResponse(
        status_code=status,
        content={
            "code": code,
            "message": message,
            "traceId": trace_id,
            "fieldErrors": fields or {},
        },
    )


def create_app(settings=None, engine=None):
    settings = settings or Settings()
    engine = engine or make_engine(settings.database_url)

    @asynccontextmanager
    async def lifespan(app):
        with engine.begin() as connection:
            bootstrap(connection, settings)
        logger.info("service_started")
        yield
        engine.dispose()
        logger.info("service_stopped")

    app = FastAPI(
        title="Villa",
        lifespan=lifespan,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    app.state.engine, app.state.settings = engine, settings
    app.state.rate_windows = {}

    @app.middleware("http")
    async def requests(request, call_next):
        started = time.monotonic()
        request.state.trace_id = uuid.uuid4().hex
        path = request.url.path
        try:
            response = None
            if request.method == "POST" and path in {
                "/auth/login",
                "/auth/signup",
                "/auth/password/reset",
            }:
                windows, instant = app.state.rate_windows, time.monotonic()
                for key in list(windows):
                    if instant - windows[key][0] >= 60:
                        del windows[key]
                ip = request.client.host if request.client else "unknown"
                start, count = windows.get(ip, (instant, 0))
                if count >= 30 or (ip not in windows and len(windows) >= 10000):
                    response = error(
                        429,
                        "RATE_LIMITED",
                        "잠시 후 다시 시도해 주세요.",
                        request.state.trace_id,
                    )
                    response.headers["Retry-After"] = "60"
                else:
                    windows[ip] = start, count + 1
            if response is None:
                response = await call_next(request)
        except Exception as exc:
            # Never log request bodies, cookies, passwords, SQL parameter values or exception messages.
            logger.error(
                "unhandled_error trace_id=%s type=%s",
                request.state.trace_id,
                type(exc).__name__,
            )
            response = error(
                500,
                "INTERNAL_ERROR",
                "요청을 처리하지 못했습니다.",
                request.state.trace_id,
            )
        response.headers.update(
            {
                "X-Request-ID": request.state.trace_id,
                "X-Content-Type-Options": "nosniff",
                "X-Frame-Options": "DENY",
                "Referrer-Policy": "same-origin",
                "Cache-Control": "no-store",
                "Content-Security-Policy": "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; frame-ancestors 'none'; form-action 'self'; base-uri 'self'",
            }
        )
        # Path excludes the query string; arbitrary path content is escaped by JSON logging.
        access.info(
            "request",
            extra={
                "request_id": request.state.trace_id,
                "method": request.method,
                "path": path,
                "status": response.status_code,
                "duration_ms": round((time.monotonic() - started) * 1000, 2),
            },
        )
        return response

    @app.exception_handler(DomainError)
    async def domain_error(request, exc):
        return error(exc.status, exc.code, exc.message, request.state.trace_id)

    @app.exception_handler(RequestValidationError)
    async def validation_error(request, exc):
        fields = {
            str(item["loc"][-1]): "입력 형식과 길이를 확인해 주세요." for item in exc.errors()
        }
        return error(
            400,
            "INVALID_INPUT",
            "입력 내용을 확인해 주세요.",
            request.state.trace_id,
            fields,
        )

    @app.exception_handler(IntegrityError)
    async def conflict(request, exc):
        return error(
            409,
            "DATA_CONFLICT",
            "이미 처리되었거나 다른 예약과 겹칩니다.",
            request.state.trace_id,
        )

    @app.exception_handler(DBAPIError)
    async def unavailable(request, exc):
        logger.error("database_unavailable trace_id=%s", request.state.trace_id)
        return error(
            503,
            "TEMPORARY_FAILURE",
            "일시적인 오류입니다. 잠시 후 다시 시도해 주세요.",
            request.state.trace_id,
        )

    @app.get("/health")
    def health():
        return {"status": "UP"}

    @app.get("/health/ready")
    def ready():
        try:
            with engine.connect() as connection:
                if (
                    connection.execute(
                        select(schedule_lock.c.id).where(schedule_lock.c.id == 1)
                    ).scalar_one_or_none()
                    == 1
                ):
                    return {"status": "UP"}
        except DBAPIError:
            pass
        return JSONResponse(status_code=503, content={"status": "DOWN"})

    def render(request, connection, name, response=None, **context):
        current = session(request, connection)
        response = response if response is not None else Response()
        token = current["csrf"] if current else issue_session(request, response, connection)
        result = templates.TemplateResponse(
            request=request, name=name, context={"csrf": token, **context}
        )
        for key, value in response.raw_headers:
            if key.lower() == b"set-cookie":
                result.raw_headers.append((key, value))
        return result

    @app.get("/login")
    @app.get("/signup")
    @app.get("/reset")
    def auth_page(request: Request, connection: DB):
        return render(request, connection, "auth.html", mode=request.url.path[1:])

    @app.get("/")
    @app.get("/manage")
    @app.get("/board")
    def page(request: Request, connection: DB):
        response = Response()
        try:
            user = actor(request, response, connection)
        except DomainError as exc:
            if exc.status == 401:
                return RedirectResponse("/login", status_code=302)
            raise
        admin = request.url.path == "/manage"
        if admin and user["role"] != "ADMIN":
            raise DomainError(403, "FORBIDDEN", "권한이 없습니다.")
        return render(
            request,
            connection,
            "board.html" if request.url.path == "/board" else "home.html",
            response=response,
            user=user,
            admin=admin,
        )

    app.include_router(accounts.router)
    app.include_router(bookings.router)
    app.include_router(board.router)
    app.mount("/assets", StaticFiles(directory=ROOT / "static" / "assets"), name="assets")
    return app


app = create_app()
