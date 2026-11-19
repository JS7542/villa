# Kangsph

가족들이 별장 이용 일정을 확인하고 예약할 수 있도록 개발한 **Spring Boot 기반 개인 프로젝트**입니다.
월별 예약 캘린더와 가족 전용 자유게시판을 중심으로, 초대코드 가입부터 관리자 승인, 예약·취소, 사용자 관리까지 구현했습니다.

가족만 사용하는 서비스의 특성에 맞춰 가입과 접근 권한을 제한하고, 같은 날짜에 예약이 겹치지 않도록 처리하는 데 중점을 두었습니다.
백엔드와 화면 구현뿐 아니라 DB 마이그레이션, Docker 배포 설정, 통합 테스트까지 구성했습니다.

## 개발 목적

별장 예약 현황을 한곳에서 확인하고, 가족들이 직접 일정을 선택해 예약할 수 있는 서비스를 만들고자 시작했습니다.
예약 기능에 필요한 날짜 충돌 처리와 사용자별 권한을 설계하고, 관리자가 일정을 조정하거나 사용자를 관리하는 흐름까지 개발 범위에 포함했습니다.

현재는 가족 내 시범 운영을 목표로 개발하고 있습니다. 실제 정원과 이용 규칙, 관리자 연락망, 개인정보 삭제 절차, 백업 복원 및 HTTPS 운영 확인은 정식 운영 전 점검 항목으로 남겨두었습니다.

## 기술 스택

| 구분 | 사용 기술 |
| --- | --- |
| 언어·프레임워크 | Java 21, Spring Boot 4.0.8 |
| 화면 | Thymeleaf, HTML, CSS, JavaScript |
| 인증·보안 | Spring Security, 서버 세션, CSRF |
| 데이터 접근 | Spring Data JPA, JdbcTemplate |
| 데이터베이스 | MySQL 8.4, PostgreSQL |
| DB 마이그레이션 | Flyway |
| 배포 구성 | Docker, Render Free, Supabase Free PostgreSQL |
| 테스트·CI | H2, MySQL·PostgreSQL 통합 테스트, Node.js, GitHub Actions |

## 주요 기능

### 회원 가입과 인증

- 초대코드로 가입한 뒤 관리자 승인을 받아 로그인하는 흐름을 구현했습니다.
- 서버 세션과 HttpOnly 쿠키로 로그인 상태를 관리합니다.
- 비밀번호 변경, 계정 정지, 권한 변경 시 기존 인증을 회수하도록 처리했습니다.
- 관리자가 비밀번호 재설정 코드를 발급할 수 있습니다.

### 별장 예약

- 월별 캘린더에서 예약 현황과 예약 불가일을 확인할 수 있습니다.
- 시작일·종료일 클릭 또는 PC 드래그로 예약 기간을 선택하고, 선택한 범위를 표시합니다.
- 예약 생성, 본인 예약 조회·취소, 변경 이력 조회를 구현했습니다.
- 예약 가능 기간, 연속 이용일, 예약 건수, 사용 인원을 서버에서 검증합니다.

### 가족 전용 자유게시판

- 글 목록·작성·조회와 본인 글 수정·삭제를 구현했습니다.
- 관리자는 게시글을 삭제할 수 있으며, 목록은 20개씩 조회합니다.
- 게시글 접근 권한, 입력 길이, 동시 수정 상황을 검증하도록 구성했습니다.

### 관리자 기능

- 초대코드 발급·폐기와 사용자 승인·정지·탈퇴 처리를 구현했습니다.
- 관리자 지정·해제를 지원하며, 마지막 관리자는 해제되지 않도록 보호합니다.
- 예약 일정 변경·강제 취소와 점검·행사 기간 등록을 지원합니다.
- 일정 변경 이력과 관리자의 직접 연락 여부, 사용자 확인 상태를 기록합니다.

## 설계와 구현

### 날짜 중복 예약 방지

예약 기간의 각 날짜를 DB에 점유 정보로 저장하고, 날짜 점유 PK와 단일 DB 잠금을 사용해 중복 확정을 막도록 구현했습니다.
예약 생성과 관리자 일정 변경에 같은 충돌 규칙을 적용해, 관리자도 이미 점유된 날짜로 예약을 변경할 수 없도록 했습니다.

같은 요청이 반복 제출되는 상황은 `Idempotency-Key`로 처리합니다.
관리자 일정 변경에는 `expectedVersion`과 변경 사유를 받아 동시 변경을 확인하고 이력을 남깁니다.

### JPA와 JdbcTemplate 병행

회원과 초대 기능에는 JPA를 사용하고, 날짜 점유를 명시적인 SQL로 처리하는 예약 기능에는 JdbcTemplate을 사용했습니다.
두 방식이 동일한 트랜잭션에 참여하도록 구성해 예약 변경이 실패하면 관련 작업도 함께 롤백되도록 했습니다.

### 세션 인증과 권한 관리

Thymeleaf 화면과 함께 사용하는 인증 방식을 서버 세션으로 구성했습니다.
로그인 성공 시 세션 ID와 CSRF 토큰을 갱신하고, 로그인·가입을 포함한 변경 요청에 CSRF 검증을 적용했습니다.
출력 이스케이프와 요청 제한을 적용하고, 본인 예약·게시글에 대한 접근 권한도 서버에서 확인합니다.

### DB 변경 관리와 테스트

DB 스키마 변경은 Flyway로 관리하도록 구성하고, 운영 환경의 `ddl-auto=update`는 제거했습니다.
로컬 검증에는 H2를 사용하며, MySQL과 PostgreSQL의 실제 동작 차이는 별도 통합 테스트로 확인하도록 CI를 구성했습니다.

## 배포 구성

비용 부담 없이 가족용 서비스를 시범 운영할 수 있도록 **Render Free 웹 서버 1개 + Supabase Free PostgreSQL**을 사용하는 배포 설정을 마련했습니다.
Thymeleaf 화면과 Spring 세션 로그인을 유지하며, Supabase는 PostgreSQL DB로 사용합니다.

- Render: Singapore 리전, 무료 플랜, 수동 배포, JVM 메모리 제한
- PostgreSQL: `postgres` 프로필과 `villa` 전용 스키마 사용
- Render 실행 프로필: `postgres,render`
- 비밀번호 등 실제 비밀값: Render 비밀 설정으로 관리
- 기존 MySQL 실행 방식과 마이그레이션 유지. MySQL 데이터의 PostgreSQL 자동 이전은 미지원

유료 인스턴스·도메인, Redis, 상시 실행 작업을 추가하지 않는 범위로 구성했습니다.
무료 한도를 넘으면 추가 비용을 들여 계속 운영하기보다 서비스를 중단하는 방침입니다.
무료 환경의 서버 절전과 DB 일시정지, 세션 소실에 따른 재로그인을 운영상 제약으로 두고 있습니다.
무료 플랜 선택만으로 초과 과금이 차단되는 것은 아니므로, 계정 결제 설정도 배포 점검 항목에 포함했습니다.

배포와 백업 절차는 [무료 배포 안내](docs/DEPLOYMENT_FREE.md), 서비스 설정은 [render.yaml](render.yaml), 환경변수 목록은 [.env.example](.env.example)에 정리했습니다.

## 로컬 실행

Java 21과 빈 MySQL DB를 준비한 뒤 아래 환경변수를 설정합니다.
PostgreSQL 실행 방법은 [무료 배포 안내](docs/DEPLOYMENT_FREE.md)에 정리했습니다.

| 변수 | 설정 내용 |
| --- | --- |
| `DB_URL` | MySQL JDBC URL. `connectionTimeZone=UTC` 지정 |
| `DB_USERNAME` / `DB_PASSWORD` | 전용 DB 계정 |
| `COOKIE_SECURE` | 운영 환경은 `true`, 로컬 HTTP 테스트만 `false` |
| `VILLA_MAX_GUESTS` | 실제 별장 정원. 기본값 `0`에서는 예약 차단 |
| `BOOTSTRAP_ENABLED` | 빈 DB에서 최초 관리자를 생성할 때만 `true` |
| `BOOTSTRAP_LOGIN_ID` / `BOOTSTRAP_PASSWORD` | 최초 관리자 계정. 기본 비밀번호 없음 |

Linux / macOS:

```sh
./gradlew bootRun
```

Windows PowerShell:

```powershell
.\gradlew.bat bootRun
```

기본 포트는 `61228`입니다. 최초 관리자 생성 후에는 bootstrap 환경변수를 제거합니다.
세부 운영 설정은 [운영 안내](docs/OPERATIONS.md)에 정리했습니다.

## 예약 규칙

예약은 시작일과 종료일을 모두 포함하도록 구현했습니다.
예를 들어 9월 10~12일 예약은 3일을 점유하며, 12일에는 다른 예약을 받을 수 없습니다.

| 항목 | 현재 기본값·처리 방식 |
| --- | --- |
| 날짜 기준 | 한국 날짜 |
| 예약 가능 기간 | 내일부터 90일 이내 |
| 연속 이용 기간 | 최대 3일 |
| 예약 보유 한도 | 진행 중·미래 예약 2건 |
| 본인 취소 | 시작일 전날까지 가능. 이후에는 관리자에게 연락 |
| 사용 인원 | 서버에 설정한 실제 별장 정원으로 검증 |
| 관리자 일정 변경 | 일반 예약과 동일하게 날짜 충돌 검증 |

기간과 건수는 초기 설정값이며, 실제 가족 이용 규칙에 맞춰 `villa.booking.*` 설정으로 조정할 수 있도록 구성했습니다.

## 테스트

동시 요청과 권한 변경처럼 실제 사용 중 문제가 생기기 쉬운 상황을 중심으로 테스트를 구성했습니다.

- 동시 예약 20건과 동시 초대 사용 10건
- 동일 요청의 중복 제출과 예약 변경 실패 시 롤백
- 점검일과 예약일 충돌
- 예약·게시글의 객체별 접근 권한
- 게시글 입력 길이와 동시 수정
- 계정 정지·비밀번호 변경 후 기존 세션 차단
- PostgreSQL 비공개 접근 제한과 DB 계정 권한
- UTC / Asia/Seoul JVM 시간대별 초대 만료 및 JPA·JDBC 저장 시각 일치

### 빠른 로컬 검증

임시 H2 DB를 사용하는 테스트:

```sh
./gradlew test
```

Windows PowerShell에서는 `.\gradlew.bat test`로 실행합니다.

달력 선택 로직은 Node.js 22 이상에서 검증합니다. npm 패키지 설치나 프런트엔드 빌드는 필요하지 않습니다.

```sh
npm test
```

### MySQL 통합 테스트

**아래 테스트는 지정한 DB의 서비스 테이블을 초기화하므로, 운영 DB가 아닌 비어 있는 별도 로컬 테스트 DB에서 실행해야 합니다.**
테스트 실행기는 localhost의 `villa_test` DB만 허용하도록 제한했습니다.

아래 환경변수 지정 예시는 Bash 기준입니다.

```sh
TEST_DB_URL='jdbc:mysql://127.0.0.1:3306/villa_test?connectionTimeZone=UTC' \
TEST_DB_USERNAME=villa_test TEST_DB_PASSWORD=local-test-secret ./gradlew test --rerun-tasks
```

### PostgreSQL 통합 테스트

Docker로 전용 테스트 DB를 실행합니다. 아래 계정은 새 로컬 컨테이너용 테스트 계정입니다.
PostgreSQL URL을 전달하면 테스트 실행기가 배포용 `postgres` 프로필을 선택합니다.

```sh
docker compose -f compose.postgres.yaml up -d --wait
TEST_DB_URL='jdbc:postgresql://127.0.0.1:55432/villa_test' \
TEST_DB_USERNAME=villa_test TEST_DB_PASSWORD=local-test-only ./gradlew test --rerun-tasks
docker compose -f compose.postgres.yaml down
```

위 컨테이너에는 영구 볼륨이 없어 `down` 실행 시 테스트 데이터가 제거됩니다.

### GitHub Actions

MySQL 8.4.9와 PostgreSQL 17.6 서비스 컨테이너에서 통합 테스트를 실행하도록 구성했습니다.
PostgreSQL은 앱 계정과 마이그레이션 계정을 분리하고, 연결 풀 3개로 통합 테스트와 최소 권한 검증을 수행합니다.
시간대에 따른 차이를 확인하기 위해 UTC와 Asia/Seoul 두 JVM 시간대도 검증 대상에 포함했습니다.

## 주요 API

| 경로 | 기능 |
| --- | --- |
| `GET /login, /signup, /reset` | 공개 인증 화면·CSRF 발급 |
| `POST /auth/login, /auth/signup, /auth/logout` | 세션 인증·가입·로그아웃 |
| `GET /users/me` | 내 정보 |
| `POST /reservations` | 예약 생성. `Idempotency-Key` 헤더 필수 |
| `GET /reservations/calendar?year=&month=` | 가족용 월별 캘린더 |
| `GET /reservations/me, /reservations/{id}/history` | 본인 예약·변경 이력 |
| `DELETE /reservations/{id}` | 본인 예약 취소. 물리 삭제하지 않음 |
| `PATCH /admin/reservations/{id}` | 관리자 날짜 변경. `expectedVersion`·`reason` 필수 |
| `POST /admin/calendar-blocks` | 사용 불가 기간 등록 |
| `GET /admin/communications` | 직접 연락이 필요한 항목 조회 |

일정 변경 안내는 관리자가 직접 연락하고, 서비스에는 연락 여부와 사용자 확인 상태를 기록하도록 구현했습니다. 외부 메시지 자동 발송 기능은 포함하지 않았습니다.

## 이전 버전에서 변경한 부분

- JWT 인증을 서버 세션 방식으로 변경했습니다. 로그인 JSON 응답은 `accessToken` 대신 사용자 정보를 반환합니다.
- 로그인 페이지와 앱 화면의 `_csrf`, `_csrf_header` 메타로 CSRF 토큰을 제공하도록 구성했습니다. 로그인·가입을 포함한 변경 요청은 해당 토큰을 헤더로 전달해야 합니다.
- 로그인 성공 시 세션 ID와 CSRF 토큰을 갱신합니다. 이후 요청에는 다음 화면에서 발급받은 새 토큰을 사용합니다.
- 운영 환경에서 `ddl-auto=update`를 제거하고 Flyway로 스키마를 관리하도록 변경했습니다. 기존 DB는 자동 baseline하지 않으며 [별도 이전 절차](docs/OPERATIONS.md)를 사용합니다.
- 기존 미사용 초대코드는 이전 과정에서 폐기하고 새로 발급하도록 했습니다. 회원 ID와 비밀번호 해시는 유지합니다.
- 최상위 패키지 `common/auth/user/invite/reservation/admin`은 유지하고, 예약의 날짜 점유 SQL은 JdbcTemplate으로 구현했습니다. 기존 회원·초대 기능의 JPA와 동일한 트랜잭션에 참여하도록 구성했습니다.

## 프로젝트 이름과 관련 문서

서비스 화면에는 **Kangsph**라는 이름을 사용합니다. 기존 저장소·Render 서비스 이름과 DB 스키마 `villa`는 호환성을 위해 유지했습니다.
공통 로고·메타 정보·메뉴는 `templates/fragments/branding.html`, 아이콘은 `static/assets/kangsph-mark.svg`에서 관리합니다.

- [무료 배포 및 백업 절차](docs/DEPLOYMENT_FREE.md)
- [운영·개인정보·인수인계 안내](docs/OPERATIONS.md)
- [Render 배포 설정](render.yaml)
- [환경변수 예시](.env.example)
