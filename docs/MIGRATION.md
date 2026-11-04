# Spring Boot에서 FastAPI로 이전

새 설치는 루트 `docker compose up -d --build --wait`로 진행하면 됩니다. 아래 절차는 **기존 MySQL 데이터**를 이어 쓸 때만 필요합니다.

1. 기존 Spring 앱의 쓰기를 중지하고 MySQL 전체를 백업합니다.
2. 기존 스키마가 Flyway V5(가족 게시판 포함)까지 적용됐는지 확인합니다.
3. 새 Compose의 MySQL을 기동합니다: `docker compose up -d mysql --wait`.
4. 기존 덤프를 새 MySQL에 복원합니다. 새 설치용 init SQL을 기존 DB에 다시 실행하지 않습니다.
5. 기존 덤프에 없는 FastAPI 세션 테이블만 한 번 생성합니다.

```sh
docker compose cp ./mysql/upgrade/001-fastapi-sessions.sql mysql:/tmp/fastapi-sessions.sql
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot "$MYSQL_DATABASE" < /tmp/fastapi-sessions.sql'
docker compose exec -T mysql rm /tmp/fastapi-sessions.sql
```

6. `.env`에서 `BOOTSTRAP_ENABLED=false`로 지정하고 FastAPI·Nginx를 올립니다.
7. 기존 사용자로 로그인하고 예약·게시판·관리자 화면을 확인합니다.

테이블·열 이름과 기존 bcrypt 비밀번호 형식을 유지했습니다. 기존 Spring 세션은 재사용하지 않으므로 다시 로그인해야 합니다. `flyway_schema_history`는 남겨두어도 되며 FastAPI는 이를 수정하지 않습니다. 이전 후 스키마 변경은 명시적인 upgrade SQL로 관리합니다.

처음부터 FastAPI init로 만든 DB에는 `app_sessions`가 이미 있으므로 upgrade SQL을 실행하지 않습니다. 기존 덤프에 해당 테이블이 있으면 생성 여부를 먼저 확인하세요. 기존 MySQL 볼륨을 그대로 연결할 때도 계정 비밀번호 환경변수는 DB 내부 비밀번호를 변경하지 않으므로 기존 계정과 일치시켜야 합니다.

Render/Supabase PostgreSQL 데이터의 자동 이전은 포함하지 않습니다. PostgreSQL에서 넘어오는 경우 별도 데이터 변환이 필요합니다.
