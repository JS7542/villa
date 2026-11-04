package com.jinsu.villa;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jinsu.villa.admin.dto.request.UserApprovalRequest;
import com.jinsu.villa.admin.dto.request.UserRoleRequest;
import com.jinsu.villa.admin.service.AdminService;
import com.jinsu.villa.auth.dto.request.SignupRequest;
import com.jinsu.villa.auth.principal.VillaPrincipal;
import com.jinsu.villa.auth.service.AuthService;
import com.jinsu.villa.auth.service.PasswordService;
import com.jinsu.villa.board.BoardDtos;
import com.jinsu.villa.board.BoardService;
import com.jinsu.villa.common.exception.DomainException;
import com.jinsu.villa.invite.service.InviteCodeService;
import com.jinsu.villa.reservation.dto.BookingDtos.*;
import com.jinsu.villa.reservation.service.ReservationService;
import com.jinsu.villa.user.entity.User;
import com.jinsu.villa.user.enumtype.Role;
import com.jinsu.villa.user.enumtype.UserStatus;
import com.jinsu.villa.user.repository.UserRepository;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestDatabaseProfiles.class)
class ServiceIntegrationTest {
  static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
  static final String PASSWORD = "Villa-Test-Password-2026";
  static final JsonMapper JSON = JsonMapper.builder().build();

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    String url = System.getenv("TEST_DB_URL");
    if (url != null) {
      // This suite deletes application tables. Refuse non-local or non-test databases.
      if (!url.matches(
          "jdbc:(postgresql|mysql)://(localhost|127\\.0\\.0\\.1|\\[::1\\])(:[0-9]+)?/villa_test(\\?.*)?"))
        throw new IllegalArgumentException("TEST_DB_URL must target a local villa_test database");
      registry.add("spring.datasource.url", () -> url);
      registry.add(
          "spring.datasource.username",
          () -> System.getenv().getOrDefault("TEST_DB_USERNAME", "root"));
      registry.add(
          "spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
      if (url.startsWith("jdbc:postgresql:")) {
        registry.add("spring.flyway.create-schemas", () -> true);
      }
      if (System.getenv("TEST_DB_MIGRATION_USERNAME") != null) {
        registry.add("spring.flyway.user", () -> System.getenv("TEST_DB_MIGRATION_USERNAME"));
        registry.add("spring.flyway.password", () -> System.getenv("TEST_DB_MIGRATION_PASSWORD"));
      }
    }
  }

  @TestConfiguration
  static class FixedTime {
    @Bean
    @Primary
    Clock testClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }

  @Autowired ReservationService bookings;
  @Autowired BoardService board;
  @Autowired InviteCodeService invites;
  @Autowired AdminService admins;
  @Autowired AuthService auth;
  @Autowired PasswordService passwords;
  @Autowired UserRepository users;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;
  @Autowired MockMvc mvc;
  VillaPrincipal owner, other, admin;
  String hash;

  @BeforeEach
  void reset() {
    for (String table :
        List.of(
            "board_posts",
            "communication_tasks",
            "reservation_history",
            "calendar_occupancy",
            "calendar_blocks",
            "reservations",
            "password_reset_tokens",
            "admin_audit",
            "invite_codes",
            "users")) jdbc.update("DELETE FROM " + table);
    hash = encoder.encode(PASSWORD);
    owner = user("family_one", Role.USER, UserStatus.ACTIVE);
    other = user("family_two", Role.USER, UserStatus.ACTIVE);
    admin = user("operator", Role.ADMIN, UserStatus.ACTIVE);
  }

  VillaPrincipal user(String login, Role role, UserStatus status) {
    return VillaPrincipal.from(
        users.saveAndFlush(
            User.builder()
                .loginId(login)
                .name(login)
                .password(hash)
                .role(role)
                .status(status)
                .build()),
        NOW);
  }

  Create input(int first, int last) {
    return new Create(LocalDate.of(2026, 9, first), LocalDate.of(2026, 9, last), 3, "private memo");
  }

  String key() {
    return UUID.randomUUID().toString();
  }

  int count(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
  }

  SignupRequest signup(String login, String code) {
    return JSON.readValue(
        "{\"loginId\":\""
            + login
            + "\",\"password\":\""
            + PASSWORD
            + "\",\"name\":\"family\",\"inviteCode\":\""
            + code
            + "\"}",
        SignupRequest.class);
  }

  <T> List<T> race(int n, java.util.function.IntFunction<T> work) throws Exception {
    var gate = new CountDownLatch(1);
    var ready = new CountDownLatch(n);
    try (var executor = Executors.newFixedThreadPool(n)) {
      var futures =
          IntStream.range(0, n)
              .mapToObj(
                  i ->
                      executor.submit(
                          () -> {
                            ready.countDown();
                            if (!gate.await(10, TimeUnit.SECONDS))
                              throw new IllegalStateException("barrier timed out");
                            return work.apply(i);
                          }))
              .toList();
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      gate.countDown();
      List<T> result = new ArrayList<>();
      for (var f : futures) result.add(f.get(30, TimeUnit.SECONDS));
      return result;
    }
  }

  MockHttpSession login(String name) throws Exception {
    return (MockHttpSession)
        mvc.perform(
                post("/auth/login")
                    .with(csrf())
                    .contentType("application/json")
                    .content("{\"loginId\":\"" + name + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getRequest()
            .getSession(false);
  }

  @Test
  void sameDateTwentyRequestsConfirmExactlyOne() throws Exception {
    var result =
        race(
            20,
            i -> {
              try {
                bookings.create(owner, input(10, 10), key());
                return 201;
              } catch (DomainException e) {
                return e.status().value();
              }
            });
    assertThat(result).filteredOn(x -> x == 201).hasSize(1);
    assertThat(result).filteredOn(x -> x == 409).hasSize(19);
    assertThat(count("reservations")).isEqualTo(1);
    assertThat(count("calendar_occupancy")).isEqualTo(1);
    assertThat(count("reservation_history")).isEqualTo(1);
  }

  @Test
  void sameRequestKeyReplaysAcrossConcurrentRetries() throws Exception {
    String k = key();
    var result = race(10, i -> bookings.create(owner, input(10, 12), k).id());
    assertThat(new HashSet<>(result)).hasSize(1);
    assertThat(count("calendar_occupancy")).isEqualTo(3);
    assertThatThrownBy(() -> bookings.create(owner, input(11, 12), k))
        .isInstanceOf(DomainException.class)
        .hasMessageContaining("요청 키");
  }

  @Test
  void overlappingMultiDayReservationsCannotBothWin() throws Exception {
    var result =
        race(
            2,
            i -> {
              try {
                bookings.create(
                    i == 0 ? owner : other, i == 0 ? input(10, 12) : input(12, 14), key());
                return true;
              } catch (DomainException e) {
                assertThat(e.code()).isEqualTo("DATE_CONFLICT");
                return false;
              }
            });
    assertThat(result).containsExactlyInAnyOrder(true, false);
    assertThat(count("calendar_occupancy")).isEqualTo(3);
  }

  @Test
  void failedMoveRestoresAllOriginalOccupancyAndHistory() {
    var a = bookings.create(owner, input(10, 12), key());
    bookings.create(other, input(15, 16), key());
    assertThatThrownBy(
            () ->
                bookings.change(
                    admin,
                    a.id(),
                    new Change(
                        LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 16), 2, 0L, "test")))
        .isInstanceOf(DomainException.class);
    assertThat(bookings.detail(owner, a.id()).startDate()).isEqualTo(LocalDate.of(2026, 9, 10));
    assertThat(count("calendar_occupancy")).isEqualTo(5);
    assertThat(count("reservation_history")).isEqualTo(2);
    assertThat(count("communication_tasks")).isZero();
  }

  @Test
  void cancelReleasesDatesAndReplayDoesNotRecreate() {
    String k = key();
    var a = bookings.create(owner, input(10, 12), k);
    bookings.cancel(owner, a.id());
    bookings.cancel(owner, a.id());
    assertThat(count("calendar_occupancy")).isZero();
    assertThat(count("reservation_history")).isEqualTo(2);
    assertThat(bookings.create(owner, input(10, 12), k).status()).isEqualTo("CANCELLED");
    bookings.create(other, input(10, 12), key());
    assertThat(count("calendar_occupancy")).isEqualTo(3);
  }

  @Test
  void blockAndReservationUseSameCalendar() {
    long id =
        bookings.block(
            admin, new Block(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 12), "maintenance"));
    assertThatThrownBy(() -> bookings.create(owner, input(11, 11), key()))
        .isInstanceOf(DomainException.class);
    bookings.release(admin, id, new Cancel(0L, "finished"));
    bookings.create(owner, input(11, 11), key());
    assertThatThrownBy(
            () ->
                bookings.block(
                    admin,
                    new Block(LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 13), "conflict")))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void crossMonthQueryAndPrivacyAreCorrect() {
    var a =
        bookings.create(
            owner,
            new Create(
                LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 2), 3, "never in calendar"),
            key());
    assertThat(bookings.calendar(other, 2026, 9)).hasSize(1);
    assertThat(bookings.calendar(other, 2026, 10)).hasSize(1);
    assertThatThrownBy(() -> bookings.detail(other, a.id())).isInstanceOf(DomainException.class);
    assertThat(JSON.writeValueAsString(bookings.calendar(other, 2026, 10)))
        .doesNotContain("memo", "never in calendar");
  }

  @Test
  void administratorEditHasVersionAndNotice() {
    var a = bookings.create(owner, input(10, 11), key());
    bookings.change(
        admin,
        a.id(),
        new Change(LocalDate.of(2026, 9, 12), LocalDate.of(2026, 9, 13), 2, 0L, "가족 협의"));
    assertThat(count("communication_tasks")).isEqualTo(1);
    assertThatThrownBy(
            () ->
                bookings.change(
                    admin,
                    a.id(),
                    new Change(
                        LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 15), 2, 0L, "stale")))
        .isInstanceOf(DomainException.class);
    long id = bookings.notices(owner, false).getFirst().id();
    assertThatThrownBy(() -> bookings.acknowledge(other, id)).isInstanceOf(DomainException.class);
    bookings.contact(admin, id);
    bookings.acknowledge(owner, id);
    assertThat(bookings.notices(owner, false).getFirst().status()).isEqualTo("ACKNOWLEDGED");
  }

  @Test
  void invitationConsumedOnlyOnceUnderTenConcurrentSignups() throws Exception {
    String code = invites.issue(admin).code();
    var result =
        race(
            10,
            i -> {
              try {
                auth.signup(signup("new_family_" + i, code));
                return true;
              } catch (DomainException e) {
                return false;
              }
            });
    assertThat(result).filteredOn(Boolean::booleanValue).hasSize(1);
    assertThat(count("users")).isEqualTo(4);
    assertThat(jdbc.queryForObject("SELECT status FROM invite_codes", String.class))
        .isEqualTo("USED");
    assertThat(jdbc.queryForObject("SELECT code FROM invite_codes", String.class))
        .isNotEqualTo(code);
  }

  @Test
  void expiredInviteAtBoundaryAndDuplicateSignupDoNotConsume() {
    var code = invites.issue(admin);
    assertThat(
            jdbc.<LocalDateTime>queryForObject(
                "SELECT expires_at FROM invite_codes WHERE id=?",
                (rs, i) -> rs.getObject(1, LocalDateTime.class),
                code.id()))
        .isEqualTo(
            LocalDateTime.ofInstant(
                NOW.plus(7, java.time.temporal.ChronoUnit.DAYS), ZoneOffset.UTC));
    assertThat(
            jdbc.<LocalDateTime>queryForObject(
                "SELECT created_at FROM users WHERE id=?",
                (rs, i) -> rs.getObject(1, LocalDateTime.class),
                owner.id()))
        .isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    jdbc.update(
        "UPDATE invite_codes SET expires_at=? WHERE id=?",
        LocalDateTime.ofInstant(NOW, ZoneOffset.UTC),
        code.id());
    assertThatThrownBy(() -> auth.signup(signup("new_family", code.code())))
        .isInstanceOf(DomainException.class);
    var fresh = invites.issue(admin);
    assertThatThrownBy(() -> auth.signup(signup("FAMILY_ONE", fresh.code())))
        .isInstanceOf(DomainException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM invite_codes WHERE id=?", String.class, fresh.id()))
        .isEqualTo("ACTIVE");
  }

  @Test
  void securityEnforcesCsrfRolesAndSuspendedSession() throws Exception {
    mvc.perform(get("/reservations/calendar").param("year", "2026").param("month", "9"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/auth/login").contentType("application/json").content("{}"))
        .andExpect(status().isForbidden());
    var session = login("family_one");
    mvc.perform(get("/admin/users/pending").session(session)).andExpect(status().isForbidden());
    admins.changeUserStatus(
        admin, owner.id(), new UserApprovalRequest(UserStatus.SUSPENDED, "요청 확인"));
    mvc.perform(get("/users/me").session(session)).andExpect(status().isUnauthorized());
    assertThatThrownBy(() -> bookings.create(owner, input(10, 10), key()))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void passwordResetInvalidatesSessionsAndCannotBeReused() throws Exception {
    var session = login("family_one");
    String token = passwords.issue(admin, owner.id(), "가족 연락 확인");
    passwords.reset(token, "New-Family-Password-2026");
    mvc.perform(get("/users/me").session(session)).andExpect(status().isUnauthorized());
    assertThatThrownBy(() -> passwords.reset(token, PASSWORD)).isInstanceOf(DomainException.class);
  }

  @Test
  void pendingLoginFailsAndLogoutInvalidatesSession() throws Exception {
    user("pending", Role.USER, UserStatus.PENDING);
    mvc.perform(
            post("/auth/login")
                .with(csrf())
                .contentType("application/json")
                .content("{\"loginId\":\"pending\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isForbidden());
    var session = login("family_one");
    mvc.perform(post("/auth/logout").session(session).with(csrf()))
        .andExpect(status().isNoContent());
    assertThat(session.isInvalid()).isTrue();
  }

  @Test
  void simultaneousAdminSuspensionsLeaveOneActive() throws Exception {
    var second = user("operator_two", Role.ADMIN, UserStatus.ACTIVE);
    var result =
        race(
            2,
            i -> {
              try {
                var actor = i == 0 ? admin : second;
                admins.changeUserStatus(
                    actor, actor.id(), new UserApprovalRequest(UserStatus.SUSPENDED, "self"));
                return true;
              } catch (DomainException e) {
                return false;
              }
            });
    assertThat(result).containsExactlyInAnyOrder(true, false);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE role='ADMIN' AND status='ACTIVE'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void boundariesCapacityAndLimitAreEnforced() {
    assertThatThrownBy(() -> bookings.create(owner, input(1, 1), key()))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> bookings.create(owner, input(10, 13), key()))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(
            () ->
                bookings.create(
                    owner,
                    new Create(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 10), 11, ""),
                    key()))
        .isInstanceOf(DomainException.class);
    bookings.create(owner, input(10, 10), key());
    bookings.create(owner, input(12, 12), key());
    assertThatThrownBy(() -> bookings.create(owner, input(14, 14), key()))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void roleChangesRevokeSessionsAndKeepLastAdministrator() throws Exception {
    assertThatThrownBy(
            () -> admins.changeUserRole(admin, admin.id(), new UserRoleRequest(Role.USER, "last")))
        .isInstanceOf(DomainException.class)
        .hasMessageContaining("마지막");
    var session = login("family_one");
    admins.changeUserRole(admin, owner.id(), new UserRoleRequest(Role.ADMIN, "보조 관리자 지정"));
    mvc.perform(get("/admin/users").session(session)).andExpect(status().isUnauthorized());
    var promoted = login("family_one");
    mvc.perform(get("/admin/users").session(promoted)).andExpect(status().isOk());
    admins.changeUserRole(admin, owner.id(), new UserRoleRequest(Role.USER, "담당 종료"));
    mvc.perform(get("/admin/users").session(promoted)).andExpect(status().isUnauthorized());
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM admin_audit WHERE action='USER_ROLE'", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void oldBlockRemainsVisibleAndReleasableAfterManyReleasedRows() {
    long block =
        bookings.block(
            admin, new Block(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 10), "active"));
    List<Object[]> rows = IntStream.range(0, 501).mapToObj(i -> new Object[] {admin.id()}).toList();
    jdbc.batchUpdate(
        "INSERT INTO calendar_blocks(start_date,end_date,reason,status,created_by,created_at)"
            + " VALUES('2026-10-01','2026-10-01','old','RELEASED',?,'2026-09-01')",
        rows);
    assertThat(bookings.calendar(owner, 2026, 9)).extracting(CalendarItem::id).contains(block);
    assertThat(bookings.blocks()).extracting(BlockView::id).contains(block);
    bookings.release(admin, block, new Cancel(0L, "finished"));
    assertThat(bookings.calendar(owner, 2026, 9)).isEmpty();
  }

  @Test
  void pendingNoticeIsNotHiddenByAcknowledgedHistory() {
    var reservation = bookings.create(owner, input(10, 10), key());
    bookings.change(
        admin,
        reservation.id(),
        new Change(LocalDate.of(2026, 9, 12), LocalDate.of(2026, 9, 12), 2, 0L, "pending"));
    long pending = bookings.notices(owner, false).getFirst().id();
    long history =
        jdbc.queryForObject(
            "SELECT history_id FROM communication_tasks WHERE id=?", Long.class, pending);
    for (int i = 0; i < 201; i++) {
      jdbc.update(
          "INSERT INTO"
              + " reservation_history(reservation_id,actor_id,action,reason,after_value,created_at)"
              + " VALUES(?,?,'UPDATE','old','old','2026-09-01')",
          reservation.id(),
          admin.id());
      long h = jdbc.queryForObject("SELECT MAX(id) FROM reservation_history", Long.class);
      jdbc.update(
          "INSERT INTO communication_tasks(history_id,recipient_id,status)"
              + " VALUES(?,?,'ACKNOWLEDGED')",
          h,
          owner.id());
    }
    assertThat(bookings.notices(owner, false).getFirst().id()).isEqualTo(pending);
    assertThat(bookings.notices(admin, true).getFirst().id()).isEqualTo(pending);
  }

  @Test
  void htmlPagesRenderAndValidationReturns400() throws Exception {
    mvc.perform(get("/login"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("로그인")));
    var session = login("family_one");
    mvc.perform(get("/").session(session))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("예약 달력")));
    mvc.perform(
            post("/reservations")
                .session(session)
                .with(csrf())
                .header("Idempotency-Key", key())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void publicReadinessChecksDatabaseAndDoesNotCreateSession() throws Exception {
    var response = mvc.perform(get("/health/ready"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"status\":\"UP\"}"))
        .andReturn();
    assertThat(response.getRequest().getSession(false)).isNull();
  }

  @Test
  void databaseConstraintRejectsSecondOwnerForOccupiedDate() {
    var first = bookings.create(owner, input(10, 10), key());
    var second = bookings.create(other, input(12, 12), key());
    assertThatThrownBy(() -> jdbc.update(
        "INSERT INTO calendar_occupancy(use_date,reservation_id) VALUES(?,?)",
        LocalDate.of(2026, 9, 10), second.id()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(jdbc.queryForObject(
        "SELECT reservation_id FROM calendar_occupancy WHERE use_date=?",
        Long.class, LocalDate.of(2026, 9, 10))).isEqualTo(first.id());
  }

  @Test
  void boardOwnershipVersionsAndAdministratorModeration() {
    var post = board.create(owner, new BoardDtos.Create("  준비물  ", " 수건을 챙겨 주세요.\n두 번째 줄 "));
    assertThat(post.title()).isEqualTo("준비물");
    assertThat(post.body()).contains("\n");
    assertThat(board.detail(other, post.id()).editable()).isFalse();
    assertThat(board.detail(admin, post.id()).editable()).isFalse();
    assertThatThrownBy(() -> board.update(other, post.id(), new BoardDtos.Update("수정", "내용", 0L)))
        .isInstanceOf(DomainException.class).hasMessageContaining("권한");
    assertThatThrownBy(() -> board.delete(other, post.id(), 0))
        .isInstanceOf(DomainException.class).hasMessageContaining("권한");
    var edited = board.update(owner, post.id(), new BoardDtos.Update("준비물 추가", "수건과 세면도구", 0L));
    assertThat(edited.version()).isEqualTo(1);
    assertThatThrownBy(() -> board.update(owner, post.id(), new BoardDtos.Update("옛 내용", "덮어쓰기", 0L)))
        .isInstanceOf(DomainException.class).hasMessageContaining("변경");
    assertThatThrownBy(() -> board.delete(owner, post.id(), 0))
        .isInstanceOf(DomainException.class).hasMessageContaining("변경");
    board.delete(admin, post.id(), edited.version());
    assertThatThrownBy(() -> board.detail(owner, post.id())).isInstanceOf(DomainException.class);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit WHERE action='BOARD_DELETE'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void boardPaginationIsBoundedAndDoesNotLeakBodies() {
    for (int i = 0; i < 23; i++) board.create(owner, new BoardDtos.Create("글 " + i, "본문 " + i));
    var first = board.list(other, null);
    assertThat(first.items()).hasSize(20);
    assertThat(first.hasNext()).isTrue();
    var next = board.list(other, first.items().getLast().id());
    assertThat(next.items()).hasSize(3);
    assertThat(next.hasNext()).isFalse();
    assertThat(next.items()).extracting(BoardDtos.Summary::id)
        .doesNotContainAnyElementsOf(first.items().stream().map(BoardDtos.Summary::id).toList());
    assertThat(JSON.writeValueAsString(first)).doesNotContain("body", "본문");
  }

  @Test
  void boardRequiresLoginCsrfAndActiveMembership() throws Exception {
    mvc.perform(get("/board").servletPath("/board")).andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/login"));
    mvc.perform(get("/board/posts")).andExpect(status().isUnauthorized());
    var session = login("family_one");
    mvc.perform(get("/board").session(session)).andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("자유게시판")));
    mvc.perform(post("/board/posts").session(session).contentType("application/json")
        .content("{\"title\":\"제목\",\"body\":\"내용\"}"))
        .andExpect(status().isForbidden());
    var post = board.create(owner, new BoardDtos.Create("제목", "내용"));
    admins.changeUserStatus(admin, owner.id(), new UserApprovalRequest(UserStatus.SUSPENDED, "요청 확인"));
    mvc.perform(get("/board/posts").session(session)).andExpect(status().isUnauthorized());
    assertThatThrownBy(() -> board.detail(owner, post.id())).isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> board.create(owner, new BoardDtos.Create("제목", "내용")))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void boardApiValidatesInputAndKeepsTextAsData() throws Exception {
    var session = login("family_one");
    for (var invalid : List.of(new BoardDtos.Create(" ", "내용"),
        new BoardDtos.Create("제목", " "), new BoardDtos.Create("가".repeat(101), "내용"),
        new BoardDtos.Create("제목", "가".repeat(5001)))) {
      mvc.perform(post("/board/posts").session(session).with(csrf()).contentType("application/json")
          .content(JSON.writeValueAsString(invalid))).andExpect(status().isBadRequest());
    }
    String unsafe = "<img src=x onerror=alert(1)>";
    var response = mvc.perform(post("/board/posts").session(session).with(csrf())
        .contentType("application/json").content(JSON.writeValueAsString(new BoardDtos.Create("제목", unsafe))))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.body").value(unsafe)).andReturn();
    long id = JSON.readTree(response.getResponse().getContentAsString()).get("id").asLong();
    var otherSession = login("family_two");
    mvc.perform(patch("/board/posts/" + id).session(otherSession).with(csrf())
        .contentType("application/json").content("{\"title\":\"침범\",\"body\":\"내용\",\"expectedVersion\":0}"))
        .andExpect(status().isForbidden());
    mvc.perform(delete("/board/posts/" + id).param("expectedVersion", "0").session(session).with(csrf()))
        .andExpect(status().isNoContent());
    mvc.perform(get("/board/posts/" + id).session(session)).andExpect(status().isNotFound());
  }

  @Test
  void concurrentBoardEditsDoNotOverwriteEachOther() throws Exception {
    var post = board.create(owner, new BoardDtos.Create("원본", "원본 내용"));
    var results = race(2, i -> {
      try {
        board.update(owner, post.id(), new BoardDtos.Update("수정 " + i, "내용 " + i, 0L));
        return true;
      } catch (DomainException e) { return false; }
    });
    assertThat(results).containsExactlyInAnyOrder(true, false);
    assertThat(board.detail(owner, post.id()).version()).isEqualTo(1);
  }

  @Test
  void postgresRuntimeRoleCannotChangeSchemaOrMigrationHistory() {
    Assumptions.assumeTrue("villa_app".equals(System.getenv("TEST_DB_USERNAME")));
    assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo("villa");
    assertThat(jdbc.queryForObject(
        "SELECT has_schema_privilege(current_user, 'villa', 'CREATE')", Boolean.class)).isFalse();
    assertThat(jdbc.queryForObject(
        "SELECT has_table_privilege(current_user, 'villa.flyway_schema_history', 'UPDATE')",
        Boolean.class)).isFalse();
    for (String role : List.of("anon", "authenticated")) {
      assertThat(jdbc.queryForObject(
          "SELECT has_schema_privilege(?, 'villa', 'USAGE')", Boolean.class, role)).isFalse();
      assertThat(jdbc.queryForObject(
          "SELECT has_table_privilege(?, 'villa.users', 'SELECT')", Boolean.class, role)).isFalse();
      assertThat(jdbc.queryForObject(
          "SELECT has_table_privilege(?, 'villa.board_posts', 'SELECT')", Boolean.class, role)).isFalse();
    }
  }
}
