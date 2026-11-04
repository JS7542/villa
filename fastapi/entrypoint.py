import os
from pathlib import Path

log_dir = Path("/var/log/villa")
log_dir.mkdir(parents=True, exist_ok=True)
if os.getuid() == 0:
    os.chown(log_dir, 10001, 10001)
    for path in log_dir.iterdir():
        if path.is_file() and not path.is_symlink():
            os.chown(path, 10001, 10001)
    os.setgroups([])
    os.setgid(10001)
    os.setuid(10001)
os.execvp(
    "uvicorn",
    [
        "uvicorn",
        "app.main:app",
        "--host",
        "0.0.0.0",
        "--port",
        "8000",
        "--workers",
        "1",
        "--proxy-headers",
        "--forwarded-allow-ips",
        "*",
        "--no-access-log",
        "--log-config",
        "logging.json",
    ],
)
