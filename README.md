# 가족 공동 별장 예약 서비스

초대코드와 관리자 승인으로 가입하고, 가족끼리 한 채의 별장을 예약하는 Spring Boot 웹 서비스입니다.

## 무료 배포: Render + Supabase

권장 구성은 **Render Free 웹 서버 1개 + Supabase Free PostgreSQL**입니다.
기존 화면과 Spring 세션 로그인을 함께 유지하며 Supabase Auth나 별도 프런트엔드는 사용하지 않습니다.
유료 인스턴스·도메인·Redis·상시 실행 작업은 추가하지 않습니다.

- [무료 배포 및 백업 절차](docs/DEPLOYMENT_FREE.md): 계정 준비, DB 권한, 환경변수, 최초 관리자, 절전·장애 대응
- [render.yaml](render.yaml): 무료 플랜 명시, Singapore, 수동 배포, JVM 메모리 제한
- [환경변수 예시](.env.example): 실제 비밀번호는 Render 비밀 설정에만 입력
- PostgreSQL은 `postgres` 프로필과 `villa` 전용 스키마를 사용합니다. Render에서는 `postgres,render`를 사용합니다.
- 기존 MySQL 마이그레이션과 기본 실행 방식은 유지합니다. 기존 MySQL 데이터는 자동 이전하지 않습니다.

무료 한도 초과 시 추가 결제 대신 중단을 허용하는 운영입니다. 서버 절전·DB 일시정지와 재로그인이 발생할 수 있으며,
무료 플랜 선택만으로 초과 과금이 막히지는 않으므로 계정 결제 설정도 반드시 확인하세요.

## 구현된 흐름

- 가입 → 관리자 승인 → 세션 로그인 → 월별 캘린더 → 예약·취소
- 관리자 초대 발급·폐기, 사용자 승인·정지·탈퇴 처리, 비밀번호 재설정 코드 발급
- 관리자 지정·해제와 마지막 관리자 보호, 권한 변경 즉시 기존 세션 회수
- 관리자 일정 변경·강제 취소, 점검·행사 기간 등록, 변경 이력과 직접 연락·사용자 확인 기록
- 비밀번호 변경·정지 후 기존 인증 회수, CSRF, 출력 이스케이프, 요청 제한
- DB 날짜 점유 PK와 단일 DB 잠금으로 중복 확정 방지, 재요청 키로 중복 제출 방지
- Flyway, 운영 기본 설정, Dockerfile, MySQL·PostgreSQL 통합 테스트용 GitHub Actions

**가족 시범 운영용이며 정식 서비스 준비 완료를 뜻하지 않습니다.** 실제 정원·현장 규칙·관리자 연락망·개인정보 삭제 절차·백업 복원·HTTPS 운영을 별도로 확인해야 합니다.

## 실행

Java 21, MySQL 8.4 또는 PostgreSQL을 사용합니다. Spring Boot 4.0.8, Thymeleaf, Spring Security, JPA/JdbcTemplate, Flyway로 구성했습니다.
아래는 기존 MySQL 실행 절차이며 PostgreSQL은 [무료 배포 안내](docs/DEPLOYMENT_FREE.md)를 따릅니다.

빈 DB를 준비하고 다음 환경변수를 지정하세요.

| 변수 | 의미 |
| --- | --- |
| DB_URL | MySQL JDBC URL. connectionTimeZone=UTC 지정 |
| DB_USERNAME / DB_PASSWORD | 전용 DB 계정 |
| COOKIE_SECURE | 운영 true. 로컬 HTTP 시험만 false |
| VILLA_MAX_GUESTS | 실제 별장 정원. 기본 0이면 예약 차단 |
| BOOTSTRAP_ENABLED | 빈 DB의 최초 관리자 생성 때만 true |
| BOOTSTRAP_LOGIN_ID / BOOTSTRAP_PASSWORD | 최초 관리자. 기본 비밀번호 없음 |

```sh
./gradlew bootRun
```

Windows에서는 `gradlew.bat bootRun`을 사용합니다. 기본 포트는 61228입니다.
첫 기동 후 bootstrap 환경변수는 제거하세요. 운영 설정은 [운영 안내](docs/OPERATIONS.md)를 읽어주세요.

## 이전 버전과 달라진 점

- **JWT 대신 서버 세션을 사용합니다.** 브라우저는 HttpOnly 쿠키로 로그인합니다. 로그인 JSON 응답은 accessToken이 아니라 사용자 정보입니다.
- 변경 요청은 로그인 페이지·앱 화면의 `_csrf` 및 `_csrf_header` 메타에서 가져온 토큰을 헤더로 보내야 합니다. 로그인·가입도 예외가 아닙니다.
- 로그인 성공 시 세션 ID와 CSRF 토큰이 갱신됩니다. 다음 화면으로 이동한 뒤 새 토큰을 사용하세요.
- 운영 `ddl-auto=update`를 제거했습니다. 기존 DB는 자동 baseline하지 않으며 [별도 이전 절차](docs/OPERATIONS.md)를 따라야 합니다.
- 기존 미사용 초대코드는 이전 과정에서 폐기하고 새 코드로 다시 발급합니다. 회원 ID·비밀번호 해시는 유지합니다.
- 최상위 패키지 common/auth/user/invite/reservation/admin은 유지했습니다. 예약의 명시적 점유 SQL은 JdbcTemplate으로 구현했고 기존 회원·초대는 JPA를 유지합니다. 동일 트랜잭션에 참여합니다.

## 예약 규칙

시작·종료일을 모두 포함합니다. 9월 10~12일은 3일 점유이며 12일에 다른 예약을 할 수 없습니다.
한국 날짜 기준 내일부터 90일 이내, 최대 연속 3일, 진행 중·미래 예약 2건이 기본 제안값입니다.
실제 가족 합의 후 `villa.booking.*` 설정을 확정하세요. 본인 취소는 시작일 전날까지이고 이후에는 관리자에게 연락합니다.
사용 인원은 서버의 실제 정원 설정으로 검증하며 관리자도 날짜 충돌을 우회하지 못합니다.

## 테스트

빠른 로컬 검증은 임시 H2 DB를 사용합니다.

```sh
./gradlew test
```

동시성과 운영 DB 호환성 검증에는 반드시 **비어 있는 별도 로컬 테스트 DB**를 지정하세요.
테스트는 지정된 DB의 서비스 테이블을 초기화합니다. 운영 DB를 지정하지 마세요.
테스트 실행기는 localhost의 `villa_test` DB만 허용하며 PostgreSQL URL을 받으면 배포용 `postgres` 프로필을 선택합니다.

```sh
TEST_DB_URL='jdbc:mysql://127.0.0.1:3306/villa_test?connectionTimeZone=UTC' \
TEST_DB_USERNAME=villa_test TEST_DB_PASSWORD=local-test-secret ./gradlew test --rerun-tasks
```

GitHub Actions는 MySQL 8.4.9 서비스 컨테이너에서 동일 통합 테스트를 실행합니다.
PostgreSQL 17.6 컨테이너에서도 앱·마이그레이션 계정을 분리하고 연결 풀 3개로 같은 테스트와 DB 권한 검증을 실행합니다.
UTC와 Asia/Seoul 두 JVM 시간대에서 초대 만료 및 JPA/JDBC 저장 시각의 일치도 검증합니다.
동시 예약 20건, 동시 초대 사용 10건, 중복 제출, 변경 롤백, 점검일 충돌, 객체별 권한, 정지·비밀번호 변경 후 세션 차단 등을 검증합니다.

Docker가 있다면 로컬 PostgreSQL 검증은 다음처럼 실행합니다. 새 컨테이너 전용 테스트 계정이며 운영 비밀번호가 아닙니다.

```sh
docker compose -f compose.postgres.yaml up -d --wait
TEST_DB_URL='jdbc:postgresql://127.0.0.1:55432/villa_test' \
TEST_DB_USERNAME=villa_test TEST_DB_PASSWORD=local-test-only ./gradlew test --rerun-tasks
docker compose -f compose.postgres.yaml down
```

위 컨테이너는 영구 볼륨이 없어 `down` 시 테스트 데이터가 제거됩니다. 최소 권한 계정 검증은 CI에서 별도로 수행합니다.

## 주요 API

| 경로 | 기능 |
| --- | --- |
| GET /login, /signup, /reset | 공개 인증 화면·CSRF 발급 |
| POST /auth/login, /auth/signup, /auth/logout | 세션 인증·가입·로그아웃 |
| GET /users/me | 내 정보 |
| POST /reservations | 예약. Idempotency-Key 헤더 필수 |
| GET /reservations/calendar?year=&month= | 가족용 월 캘린더 |
| GET /reservations/me, /reservations/{id}/history | 본인 예약·변경 이력 |
| DELETE /reservations/{id} | 본인 취소. 물리 삭제 아님 |
| PATCH /admin/reservations/{id} | 관리자 날짜 변경. expectedVersion·reason 필수 |
| POST /admin/calendar-blocks | 사용 불가 기간 |
| GET /admin/communications | 직접 연락할 일 |

자동 외부 메시지는 발송하지 않습니다. 변경 후 관리자가 직접 연락하고 확인 상태를 기록합니다.
상세한 한계·개인정보·운영 인수인계는 [운영 안내](docs/OPERATIONS.md)에 있습니다.
