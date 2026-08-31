package com.jinsu.villa.reservation.service;

import com.jinsu.villa.auth.principal.VillaPrincipal;
import com.jinsu.villa.common.exception.DomainException;
import com.jinsu.villa.common.util.*;
import com.jinsu.villa.reservation.dto.BookingDtos.*;
import com.jinsu.villa.reservation.repository.ReservationRepository;
import com.jinsu.villa.user.enumtype.Role;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationService {
  private final ReservationRepository reservations;
  private final JdbcTemplate jdbc;
  private final WriteGuard guard;
  private final Clock clock;
  private final Audit audit;

  @Value("${villa.booking.advance-days:90}")
  private int advanceDays;

  @Value("${villa.booking.max-days:3}")
  private int maxDays;

  @Value("${villa.booking.max-active:2}")
  private int maxActive;

  @Value("${villa.booking.max-guests:0}")
  private int maxGuests;

  private LocalDate today() {
    return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
  }

  private LocalDateTime now() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }

  public Policy policy() {
    return new Policy(
        advanceDays, maxDays, maxActive, maxGuests, today(), "Asia/Seoul", "시작일·종료일 모두 포함");
  }

  private void dates(LocalDate start, LocalDate end, int guests) {
    if (maxGuests <= 0)
      throw DomainException.conflict("CAPACITY_NOT_CONFIGURED", "관리자가 실제 정원을 설정한 뒤 예약할 수 있습니다.");
    if (start == null
        || end == null
        || end.isBefore(start)
        || !start.isAfter(today())
        || end.isAfter(today().plusDays(advanceDays))
        || ChronoUnit.DAYS.between(start, end) + 1 > maxDays)
      throw DomainException.bad("INVALID_DATES", "내일부터 예약 가능 기간 안에서 최대 " + maxDays + "일을 선택해 주세요.");
    if (guests < 1 || guests > maxGuests)
      throw DomainException.bad("CAPACITY_EXCEEDED", "이용 인원은 1~" + maxGuests + "명입니다.");
  }

  private Booking get(long id) {
    return reservations.get(id).orElseThrow(DomainException::missing);
  }

  private void visible(VillaPrincipal p, Booking r) {
    if (p.role() != Role.ADMIN && r.userId() != p.id()) throw DomainException.missing();
  }

  private void version(Booking r, long version) {
    if (r.version() != version)
      throw DomainException.conflict("STALE_VERSION", "예약이 변경되었습니다. 새로고침해 주세요.");
  }

  private String state(Booking r) {
    return r.startDate()
        + " ~ "
        + r.endDate()
        + " / "
        + r.guestCount()
        + "명 / "
        + r.status()
        + " / v"
        + r.version();
  }

  private void history(
      Booking before,
      Booking after,
      VillaPrincipal actor,
      String action,
      String reason,
      boolean notify) {
    long id =
        reservations.insert(
            "INSERT INTO"
                + " reservation_history(reservation_id,actor_id,action,reason,before_value,after_value,created_at)"
                + " VALUES(?,?,?,?,?,?,?)",
            after.id(),
            actor.id(),
            action,
            reason,
            before == null ? null : state(before),
            state(after),
            now());
    if (notify)
      jdbc.update(
          "INSERT INTO communication_tasks(history_id,recipient_id,status) VALUES(?,?,'PENDING')",
          id,
          after.userId());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Booking create(VillaPrincipal actor, Create input, String key) {
    if (key == null || !key.matches("[a-zA-Z0-9_-]{16,100}"))
      throw DomainException.bad("INVALID_REQUEST_KEY", "예약 요청 키가 올바르지 않습니다.");
    guard.lock();
    guard.actor(actor, false);
    String memo = input.memo() == null ? "" : input.memo().strip();
    String hash =
        Tokens.hash(
            input.startDate() + "|" + input.endDate() + "|" + input.guestCount() + "|" + memo);
    var replay = reservations.replay(actor.id(), key);
    if (replay.isPresent()) {
      if (!hash.equals(reservations.requestHash(replay.get().id())))
        throw DomainException.conflict("IDEMPOTENCY_MISMATCH", "같은 요청 키로 다른 예약을 만들 수 없습니다.");
      return replay.get();
    }
    dates(input.startDate(), input.endDate(), input.guestCount());
    if (reservations.active(actor.id(), today()) >= maxActive)
      throw DomainException.conflict("RESERVATION_LIMIT", "진행 중·미래 예약은 최대 " + maxActive + "건입니다.");
    if (reservations.occupied(input.startDate(), input.endDate()))
      throw DomainException.conflict("DATE_CONFLICT", "다른 예약 또는 점검 기간과 겹칩니다.");
    long id =
        reservations.insert(
            "INSERT INTO"
                + " reservations(user_id,start_date,end_date,guest_count,memo,status,version,policy_version,request_key,request_hash,created_at,updated_at)"
                + " VALUES(?,?,?,?,?,'CONFIRMED',0,'v1.1',?,?,?,?)",
            actor.id(),
            input.startDate(),
            input.endDate(),
            input.guestCount(),
            memo,
            key,
            hash,
            now(),
            now());
    reservations.occupy(input.startDate(), input.endDate(), id, null);
    Booking result = get(id);
    history(null, result, actor, "CREATE", "본인 예약", false);
    return result;
  }

  public List<Booking> mine(VillaPrincipal actor) {
    return reservations.mine(actor.id());
  }

  public Booking detail(VillaPrincipal actor, long id) {
    Booking r = get(id);
    visible(actor, r);
    return r;
  }

  public List<History> history(VillaPrincipal actor, long id) {
    detail(actor, id);
    return reservations.history(id);
  }

  public List<Booking> all() {
    return reservations.all();
  }

  public List<BlockView> blocks() {
    return reservations.blocks();
  }

  public List<CalendarItem> calendar(VillaPrincipal actor, int year, int month) {
    if (year < 2000 || year > 2100 || month < 1 || month > 12)
      throw DomainException.bad("INVALID_MONTH", "올바른 조회 월을 선택해 주세요.");
    var ym = YearMonth.of(year, month);
    var result = new ArrayList<CalendarItem>();
    for (var r : reservations.month(ym.atDay(1), ym.atEndOfMonth()))
      result.add(
          new CalendarItem(
              r.id(),
              "RESERVATION",
              r.userName(),
              r.startDate(),
              r.endDate(),
              r.userId() == actor.id()));
    for (var b : reservations.blocksInMonth(ym.atDay(1), ym.atEndOfMonth()))
      result.add(new CalendarItem(b.id(), "BLOCK", "점검·행사", b.startDate(), b.endDate(), false));
    return result;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Booking cancel(VillaPrincipal actor, long id) {
    guard.lock();
    guard.actor(actor, false);
    Booking before = get(id);
    if (before.userId() != actor.id()) throw DomainException.missing();
    if (before.status().equals("CANCELLED")) return before;
    if (!before.startDate().isAfter(today()))
      throw DomainException.conflict("CANCELLATION_CLOSED", "이용 시작일 이후에는 관리자에게 연락해 주세요.");
    return cancelInternal(actor, before, "본인 취소", false);
  }

  private Booking cancelInternal(
      VillaPrincipal actor, Booking before, String reason, boolean notify) {
    jdbc.update("DELETE FROM calendar_occupancy WHERE reservation_id=?", before.id());
    jdbc.update(
        "UPDATE reservations SET status='CANCELLED',version=version+1,updated_at=? WHERE id=?",
        now(),
        before.id());
    Booking after = get(before.id());
    history(before, after, actor, notify ? "FORCE_CANCEL" : "CANCEL", reason, notify);
    return after;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Booking forceCancel(VillaPrincipal actor, long id, Cancel input) {
    guard.lock();
    guard.actor(actor, true);
    Booking before = get(id);
    version(before, input.expectedVersion());
    if (before.status().equals("CANCELLED")) return before;
    if (before.endDate().isBefore(today()))
      throw DomainException.conflict("PAST_RESERVATION", "종료된 이용 기록은 취소할 수 없습니다.");
    return cancelInternal(actor, before, input.reason().strip(), true);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Booking change(VillaPrincipal actor, long id, Change input) {
    guard.lock();
    guard.actor(actor, true);
    Booking before = get(id);
    version(before, input.expectedVersion());
    if (!before.status().equals("CONFIRMED") || !before.startDate().isAfter(today()))
      throw DomainException.conflict("NOT_CHANGEABLE", "시작 전 확정 예약만 변경할 수 있습니다.");
    dates(input.startDate(), input.endDate(), input.guestCount());
    jdbc.update("DELETE FROM calendar_occupancy WHERE reservation_id=?", id);
    if (reservations.occupied(input.startDate(), input.endDate()))
      throw DomainException.conflict("DATE_CONFLICT", "다른 예약 또는 점검 기간과 겹칩니다.");
    reservations.occupy(input.startDate(), input.endDate(), id, null);
    jdbc.update(
        "UPDATE reservations SET"
            + " start_date=?,end_date=?,guest_count=?,version=version+1,updated_at=? WHERE id=?",
        input.startDate(),
        input.endDate(),
        input.guestCount(),
        now(),
        id);
    Booking after = get(id);
    history(before, after, actor, "UPDATE", input.reason().strip(), true);
    return after;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public long block(VillaPrincipal actor, Block input) {
    guard.lock();
    guard.actor(actor, true);
    if (input.startDate().isBefore(today())
        || input.endDate().isBefore(input.startDate())
        || input.endDate().isAfter(today().plusDays(365)))
      throw DomainException.bad("INVALID_DATES", "점검 기간은 오늘부터 1년 이내로 선택해 주세요.");
    if (reservations.occupied(input.startDate(), input.endDate()))
      throw DomainException.conflict("DATE_CONFLICT", "기존 예약 또는 점검과 겹칩니다. 예약을 먼저 조정해 주세요.");
    long id =
        reservations.insert(
            "INSERT INTO calendar_blocks(start_date,end_date,reason,status,created_by,created_at)"
                + " VALUES(?,?,?,'ACTIVE',?,?)",
            input.startDate(),
            input.endDate(),
            input.reason().strip(),
            actor.id(),
            now());
    reservations.occupy(input.startDate(), input.endDate(), null, id);
    audit.record(actor.id(), "BLOCK_CREATE", id, input.reason());
    return id;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void release(VillaPrincipal actor, long id, Cancel input) {
    guard.lock();
    guard.actor(actor, true);
    var b = reservations.block(id).orElseThrow(DomainException::missing);
    if (b.version() != input.expectedVersion())
      throw DomainException.conflict("STALE_VERSION", "점검 정보가 변경되었습니다.");
    if (b.status().equals("RELEASED")) return;
    jdbc.update("DELETE FROM calendar_occupancy WHERE block_id=?", id);
    jdbc.update("UPDATE calendar_blocks SET status='RELEASED',version=version+1 WHERE id=?", id);
    audit.record(actor.id(), "BLOCK_RELEASE", id, input.reason());
  }

  public List<Notice> notices(VillaPrincipal actor, boolean admin) {
    return reservations.notices(admin ? null : actor.id());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void contact(VillaPrincipal actor, long id) {
    guard.lock();
    guard.actor(actor, true);
    int n =
        jdbc.update(
            "UPDATE communication_tasks SET status='CONTACTED',contacted_at=? WHERE id=? AND"
                + " status='PENDING'",
            now(),
            id);
    if (n > 0) audit.record(actor.id(), "CONTACTED", id, "예약자에게 직접 연락 완료");
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void acknowledge(VillaPrincipal actor, long id) {
    guard.lock();
    guard.actor(actor, false);
    if (jdbc.update(
            "UPDATE communication_tasks SET status='ACKNOWLEDGED',acknowledged_at=? WHERE id=? AND"
                + " recipient_id=?",
            now(),
            id,
            actor.id())
        == 0) throw DomainException.missing();
  }
}
