# Villa · Kangsph

가족 별장 예약과 자유게시판 서비스입니다. **Python FastAPI + MySQL 8.4 + Nginx**를 각각 이미지로 빌드하고, 루트에서 Docker Compose로 실행합니다.

```text
villa/
├── compose.yaml
├── .env.example
├── mysql/
│   ├── Dockerfile
│   ├── conf.d/villa.cnf
│   ├── entrypoint.sh
│   ├── init/001-schema.sql         # 빈 데이터 볼륨의 최초 기동에 실행
│   └── upgrade/001-fastapi-sessions.sql
├── nginx/
│   ├── Dockerfile
│   ├── nginx.conf
│   └── conf.d/villa.conf
├── fastapi/
│   ├── Dockerfile
│   ├── requirements.txt
│   ├── logging.json
│   ├── app/                       # API, Jinja2 화면, 기존 CSS/JS
│   └── tests/
├── logs/
│   ├── mysql/                     # error.log, slow.log, entrypoint.log
│   ├── nginx/                     # access.log, error.log
│   └── fastapi/                   # app.log, access.log, error.log
├── scripts/
├── docs/
└── legacy/spring/                 # 이전 Java 구현과 배포 파일 보관
```

## 실행

Docker Engine과 Compose v2 이상이 필요합니다. 호스트에 Python·Java·MySQL 설치는 필요 없습니다.

```sh
cp .env.example .env
# .env에서 DB 비밀번호 2개, 최초 관리자 비밀번호, 실제 정원을 설정
docker compose up -d --build --wait
```

Windows PowerShell에서는 첫 줄을 `Copy-Item .env.example .env`로 실행합니다.

- 접속: `http://localhost` (`HTTP_PORT=8080`이면 `http://localhost:8080`)
- 관리자: `.env`의 `BOOTSTRAP_LOGIN_ID` / `BOOTSTRAP_PASSWORD`로 로그인 → 관리 메뉴
- `VILLA_MAX_GUESTS=0`은 정원 미설정 상태로 **예약이 차단**됩니다. 실제 정원을 입력하세요.
- 최초 관리자 생성 후 `BOOTSTRAP_ENABLED=false`로 바꾸고 관리자 비밀번호 환경변수를 지운 뒤 `docker compose up -d`를 실행하세요. 사용자가 있으면 bootstrap은 기존 계정을 변경하지 않고 건너뜁니다.
- 기본 구성은 HTTP입니다. HTTPS를 붙이면 `COOKIE_SECURE=true`로 설정하세요.

MySQL 초기화가 끝나고 준비 상태 검사를 통과하면 FastAPI가 시작되고, 그 다음 Nginx가 시작됩니다. 외부에는 Nginx 포트만 공개됩니다. 준비 순서 설정은 [Docker Compose 공식 문서](https://docs.docker.com/compose/how-tos/startup-order/)를 따릅니다.

## 이미지 빌드와 레지스트리 배포

각 서비스는 자신의 디렉토리만 빌드 컨텍스트로 사용합니다.

```sh
docker build -t villa/mysql:latest ./mysql
docker build -t villa/nginx:latest ./nginx
docker build -t villa/fastapi:latest ./fastapi
```

Docker Hub에 올릴 때 `.env`의 `IMAGE_PREFIX`를 본인 네임스페이스로 지정하세요. 예를 들어 `IMAGE_PREFIX=mydockerid`이면 `mydockerid/mysql`, `mydockerid/nginx`, `mydockerid/fastapi`가 됩니다. 다른 레지스트리는 `registry.example.com/villa`처럼 설정할 수 있습니다.

```sh
docker login
docker compose build
docker compose push
```

배포 서버에 이 저장소의 `compose.yaml`, `.env`, `logs/`를 준비하고 동일한 `IMAGE_PREFIX`, `IMAGE_TAG`로 실행합니다.

```sh
docker compose pull
docker compose up -d --no-build --wait
```

## 초기 DB와 데이터 보존

- 공식 MySQL 이미지가 `MYSQL_DATABASE`와 전용 계정을 만들고 `mysql/init/*.sql`을 순서대로 실행합니다.
- 초기 테이블과 예약 잠금 행이 자동 생성됩니다. 관리자는 비밀번호를 안전하게 해시할 수 있도록 FastAPI 최초 기동에서 생성합니다.
- DB 데이터는 `mysql_data` named volume에 보존됩니다. `docker compose down` 후 다시 올려도 유지됩니다.
- 초기화 SQL은 **데이터 볼륨이 비어 있을 때만** 실행됩니다. 이미지 재빌드나 재시작으로 기존 DB가 초기화되지 않습니다. 이 동작은 [공식 MySQL 이미지 안내](https://hub.docker.com/_/mysql/)와 같습니다.
- `docker compose down -v`는 DB 볼륨까지 삭제합니다. 기존 운영 데이터를 보존하려면 사용하지 마세요.
- 운영 기록은 DB의 `admin_audit`, `reservation_history`, `communication_tasks`에 보존하며, 서비스 실행·요청·오류 로그는 `logs/`에 별도로 남습니다.

## 유지한 주요 기능

- 초대코드 가입, 관리자 승인·정지·권한 변경, 마지막 관리자 보호
- MySQL에 저장하는 서버 세션, HttpOnly/SameSite 쿠키, CSRF 검증, 인증 요청 제한
- 비밀번호 변경·관리자 재설정 코드, 계정 변경 시 기존 세션 회수
- 월별 예약 캘린더, 예약·본인 취소, 관리자 일정 변경·강제 취소, 점검 일정
- 날짜 점유 PK와 트랜잭션 잠금으로 중복 예약 방지, `Idempotency-Key`, `expectedVersion`
- 예약 변경 이력, 관리자 연락 완료·예약자 확인
- 가족 게시판 목록·작성·조회·수정·삭제, 권한 검증과 20건 단위 조회

기존 URL과 화면 흐름을 유지하고 Thymeleaf를 Jinja2로 전환했습니다. 이전 Spring/Gradle/Render/PostgreSQL 파일은 `legacy/spring/`에 보관하고 새 배포에서는 사용하지 않습니다. 기존 MySQL 데이터가 있으면 [이전 안내](docs/MIGRATION.md)를 먼저 확인하세요. PostgreSQL 데이터 자동 이전은 포함하지 않습니다.

## 로그와 운영

```sh
docker compose ps
docker compose logs --tail=100 -f
curl http://localhost/health/ready
```

FastAPI 파일 로그와 Docker stdout 로그는 크기 제한으로 순환합니다. Nginx·MySQL 파일 로그의 호스트 순환, 백업·복원, 이미지 갱신 절차는 [운영 안내](docs/OPERATIONS.md)에 있습니다.

## 개발과 검증

Python 3.12 환경에서:

```sh
python -m pip install -r fastapi/requirements-dev.txt
python -m pytest fastapi/tests -q
ruff check fastapi
npm test
docker compose --env-file .env.example config --quiet
```

기본 API 테스트는 임시 SQLite DB를 사용합니다. 실제 MySQL 검증은 전용 `*_test` DB를 지정해 실행합니다. 이 DB의 테이블은 테스트마다 삭제·초기화됩니다.

```sh
TEST_DATABASE_URL='mysql+pymysql://root:test-password@127.0.0.1:3306/villa_test' python -m pytest fastapi/tests -q
```

MySQL 테스트는 배포용 init SQL을 직접 실행하고 동시 예약 경쟁도 검증합니다. CI에서는 SQLite·MySQL 테스트, 세 이미지 빌드, Compose 기동, 로그인·예약·게시판·로그·재기동 보존 검사를 실행합니다.

스키마 변경 후 최초 설치용 SQL 재생성:

```sh
PYTHONPATH=fastapi python scripts/generate-schema.py
```

기존 볼륨에 스키마 변경을 적용할 때는 별도의 upgrade SQL을 작성·실행해야 합니다. init 파일 수정만으로 기존 DB가 변경되지는 않습니다.
