package com.jinsu.villa.reservation.repository;

import com.jinsu.villa.reservation.dto.BookingDtos.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.*;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ReservationRepository {
  private final JdbcTemplate jdbc;
  private static final String SELECT =
      "SELECT r.*,u.name user_name FROM reservations r JOIN users u ON u.id=r.user_id ";
  private final RowMapper<Booking> mapper =
      (rs, i) ->
          new Booking(
              rs.getLong("id"),
              rs.getLong("user_id"),
              rs.getString("user_name"),
              rs.getObject("start_date", LocalDate.class),
              rs.getObject("end_date", LocalDate.class),
              rs.getInt("guest_count"),
              rs.getString("memo"),
              rs.getString("status"),
              rs.getLong("version"));

  public long insert(String sql, Object... values) {
    KeyHolder key = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          // PostgreSQL otherwise returns every inserted column, not just the generated ID.
          var st = connection.prepareStatement(sql, new String[] {"id"});
          for (int i = 0; i < values.length; i++) st.setObject(i + 1, values[i]);
          return st;
        },
        key);
    return Objects.requireNonNull(key.getKey()).longValue();
  }

  public Optional<Booking> get(long id) {
    return jdbc.query(SELECT + "WHERE r.id=?", mapper, id).stream().findFirst();
  }

  public List<Booking> mine(long userId) {
    return jdbc.query(
        SELECT + "WHERE r.user_id=? ORDER BY r.start_date DESC,r.id DESC LIMIT 500",
        mapper,
        userId);
  }

  public List<Booking> all() {
    return jdbc.query(SELECT + "ORDER BY r.start_date DESC,r.id DESC LIMIT 500", mapper);
  }

  public List<Booking> month(LocalDate first, LocalDate last) {
    return jdbc.query(
        SELECT
            + "WHERE r.status='CONFIRMED' AND r.start_date<=? AND r.end_date>=? ORDER BY"
            + " r.start_date",
        mapper,
        last,
        first);
  }

  public Optional<Booking> replay(long userId, String key) {
    return jdbc
        .query(SELECT + "WHERE r.user_id=? AND r.request_key=?", mapper, userId, key)
        .stream()
        .findFirst();
  }

  public String requestHash(long id) {
    return jdbc.queryForObject(
        "SELECT request_hash FROM reservations WHERE id=?", String.class, id);
  }

  public int active(long userId, LocalDate today) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM reservations WHERE user_id=? AND status='CONFIRMED' AND end_date>=?",
        Integer.class,
        userId,
        today);
  }

  public boolean occupied(LocalDate start, LocalDate end) {
    return jdbc.queryForObject(
            "SELECT COUNT(*) FROM calendar_occupancy WHERE use_date BETWEEN ? AND ?",
            Integer.class,
            start,
            end)
        > 0;
  }

  public void occupy(LocalDate start, LocalDate end, Long reservation, Long block) {
    for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1))
      jdbc.update(
          "INSERT INTO calendar_occupancy(use_date,reservation_id,block_id) VALUES(?,?,?)",
          d,
          reservation,
          block);
  }

  public List<BlockView> blocks() {
    return jdbc.query(
        "SELECT * FROM calendar_blocks ORDER BY CASE WHEN status='ACTIVE' THEN 0 ELSE 1"
            + " END,start_date DESC LIMIT 500",
        blockMapper);
  }

  private final RowMapper<BlockView> blockMapper =
      (rs, i) ->
          new BlockView(
              rs.getLong("id"), rs.getObject("start_date", LocalDate.class),
              rs.getObject("end_date", LocalDate.class), rs.getString("reason"),
              rs.getString("status"), rs.getLong("version"));

  public List<BlockView> blocksInMonth(LocalDate first, LocalDate last) {
    return jdbc.query(
        "SELECT * FROM calendar_blocks WHERE status='ACTIVE' AND start_date<=? AND end_date>=?"
            + " ORDER BY start_date",
        blockMapper,
        last,
        first);
  }

  public Optional<BlockView> block(long id) {
    return jdbc.query("SELECT * FROM calendar_blocks WHERE id=?", blockMapper, id).stream()
        .findFirst();
  }

  public List<History> history(long id) {
    return jdbc.query(
        "SELECT * FROM reservation_history WHERE reservation_id=? ORDER BY id DESC",
        (rs, i) ->
            new History(
                rs.getLong("id"),
                rs.getString("action"),
                rs.getString("reason"),
                rs.getString("before_value"),
                rs.getString("after_value"),
                rs.getObject("created_at", LocalDateTime.class)),
        id);
  }

  public List<Notice> notices(Long userId) {
    return jdbc.query(
        "SELECT c.id,h.reservation_id,u.name,h.action,h.reason,c.status,h.created_at FROM"
            + " communication_tasks c JOIN reservation_history h ON h.id=c.history_id JOIN users u"
            + " ON u.id=c.recipient_id "
            + (userId == null ? "" : "WHERE c.recipient_id=? ")
            + "ORDER BY CASE c.status WHEN 'PENDING' THEN 0 WHEN 'CONTACTED' THEN 1 ELSE 2 END,c.id"
            + " ASC LIMIT 200",
        (rs, i) ->
            new Notice(
                rs.getLong("id"),
                rs.getLong("reservation_id"),
                rs.getString("name"),
                rs.getString("action"),
                rs.getString("reason"),
                rs.getString("status"),
                rs.getObject("created_at", LocalDateTime.class)),
        userId == null ? new Object[] {} : new Object[] {userId});
  }
}
