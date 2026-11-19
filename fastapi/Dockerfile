FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 TZ=UTC
WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt \
    && groupadd --gid 10001 villa \
    && useradd --uid 10001 --gid villa --no-create-home villa
COPY app ./app
COPY logging.json entrypoint.py ./
EXPOSE 8000
# Prepare the host-mounted log directory, then drop to uid/gid 10001 before Uvicorn.
ENTRYPOINT ["python", "entrypoint.py"]
