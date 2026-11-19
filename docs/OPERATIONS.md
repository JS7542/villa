# Docker 운영

## 설정

루트 `.env`에 DB 계정·비밀번호, 정원, 최초 관리자 설정을 둡니다. `.env`, 로그, DB 덤프는 Git에 넣지 않습니다. 외부 공개 포트는 Nginx HTTP 포트 하나입니다. MySQL과 FastAPI는 Compose 내부 서비스 이름으로 통신합니다.

기본 HTTP는 로컬·초기 확인용입니다. 인터넷에서 운영할 때 HTTPS 종단을 구성하고 `COOKIE_SECURE=true`로 바꾸세요. FastAPI 이미지는 내부 프록시를 신뢰하므로 FastAPI의 8000번 포트를 외부에 직접 공개하지 마세요. Nginx는 클라이언트가 보낸 전달 헤더를 덮어씁니다.

세션은 MySQL에 저장합니다. 1시간 유휴 만료와 로그인 후 최대 12시간 유효기간을 적용합니다. 앱 재시작으로 로그인 상태가 사라지지 않으며 비밀번호·권한·계정 상태 변경 시 세션을 삭제합니다. 단일 Uvicorn worker를 사용하며 인증 요청 제한은 worker 메모리에 저장됩니다.

## 서비스별 로그

| 호스트 경로 | 내용 |
| --- | --- |
| `logs/fastapi/app.log` | 앱 기동·종료, Uvicorn 실행 기록 |
| `logs/fastapi/access.log` | 요청 ID, 경로, 메서드, 응답 상태, 처리 시간(JSON) |
| `logs/fastapi/error.log` | 앱·DB 오류 |
| `logs/nginx/access.log` | 외부 접속 IP·요청·응답·시간(JSON) |
| `logs/nginx/error.log` | 프록시·웹서버 오류 |
| `logs/mysql/entrypoint.log` | 공식 엔트리포인트의 DB 최초 초기화·시작 기록 |
| `logs/mysql/error.log` | DB 시작·종료·오류 |
| `logs/mysql/slow.log` | 2초 이상 걸린 쿼리 |

FastAPI는 요청 본문, 비밀번호, 쿠키, 쿼리 문자열, DB 오류의 SQL 매개변수를 로그에 기록하지 않습니다. MySQL general query log는 꺼두었습니다. 관리자 행동과 변경 사유는 DB 감사 테이블에 저장됩니다. 느린 쿼리 로그에는 SQL이 남으므로 로그 디렉토리 접근을 운영자로 제한하세요.

FastAPI의 각 로그 파일은 10 MiB, 백업 5개로 순환합니다. Compose의 Docker stdout도 컨테이너별 10 MiB × 5개로 제한합니다. Nginx·MySQL 파일은 호스트 `logrotate`로 관리합니다. 저장소를 `/opt/villa`에 배치했다면 다음 예시를 `/etc/logrotate.d/villa`에 설치할 수 있습니다. 다른 경로는 수정하세요.

```text
/opt/villa/logs/nginx/*.log /opt/villa/logs/mysql/*.log {
    daily
    rotate 14
    maxsize 20M
    missingok
    notifempty
    compress
    delaycompress
    copytruncate
    su root root
}
```

`copytruncate`는 서비스 중단 없이 순환하지만 복사 직후 절단 사이의 일부 기록이 유실될 수 있습니다. 엄격한 감사 원본은 DB 감사 테이블을 기준으로 확인하세요. Windows 호스트는 동등한 로그 순환 작업을 별도로 설정하세요.

## 이미지 업데이트

```sh
docker compose build
docker compose up -d --wait
```

레지스트리 이미지 사용 시 `IMAGE_TAG`를 버전별로 지정하고:

```sh
docker compose pull
docker compose up -d --no-build --wait
```

MySQL 데이터 볼륨과 호스트 로그는 컨테이너 교체 후에도 유지됩니다. Nginx는 Docker DNS를 재조회하여 교체된 FastAPI 컨테이너로 연결합니다. FastAPI는 로그 디렉토리 권한을 준비한 뒤 UID/GID 10001로 실행합니다.

## 백업과 복원

먼저 쓰기 요청을 중지합니다. 아래 덤프는 컨테이너 내부 파일로 만들고 복사하므로 Windows PowerShell의 리디렉션 인코딩 영향을 받지 않습니다.

```sh
docker compose stop nginx fastapi
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --no-tablespaces --databases "$MYSQL_DATABASE" > /tmp/villa-backup.sql'
docker compose cp mysql:/tmp/villa-backup.sql ./villa-backup.sql
docker compose exec -T mysql rm /tmp/villa-backup.sql
docker compose up -d --wait
```

복원은 대상 DB를 덮어쓰므로 백업 파일과 대상 환경을 먼저 확인하세요. 신규 스키마로 만든 백업 또는 [이전 절차](MIGRATION.md)를 마친 DB를 사용합니다.

```sh
docker compose stop nginx fastapi
docker compose cp ./villa-backup.sql mysql:/tmp/villa-backup.sql
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot < /tmp/villa-backup.sql'
docker compose exec -T mysql rm /tmp/villa-backup.sql
docker compose up -d --wait
```

덤프는 비밀번호 해시·사용자 데이터·세션 정보를 포함합니다. 보호된 위치로 옮기고 보존 기간에 맞춰 정리하세요.
