package com.jinsu.villa.board;

import com.jinsu.villa.auth.principal.VillaPrincipal;
import com.jinsu.villa.board.BoardDtos.*;
import com.jinsu.villa.common.exception.DomainException;
import com.jinsu.villa.common.util.Audit;
import com.jinsu.villa.common.util.WriteGuard;
import com.jinsu.villa.user.enumtype.Role;
import java.time.*;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BoardService {
  private final JdbcTemplate jdbc;
  private final WriteGuard guard;
  private final Audit audit;
  private final Clock clock;
  private static final String SELECT =
      "SELECT p.*,u.name author_name FROM board_posts p JOIN users u ON u.id=p.user_id ";

  public Page list(VillaPrincipal actor, Long before) {
    guard.actor(actor, false);
    if (before != null && before < 1) throw DomainException.bad("INVALID_PAGE", "목록을 다시 불러와 주세요.");
    var rows = jdbc.query(SELECT + (before == null ? "" : "WHERE p.id < ? ")
        + "ORDER BY p.id DESC LIMIT 21",
        (rs, i) -> new Summary(rs.getLong("id"), rs.getString("title"),
            rs.getString("author_name"), rs.getObject("created_at", LocalDateTime.class)),
        before == null ? new Object[] {} : new Object[] {before});
    return new Page(rows.stream().limit(20).toList(), rows.size() > 20);
  }

  public Post detail(VillaPrincipal actor, long id) {
    var user = guard.actor(actor, false);
    return jdbc.query(SELECT + "WHERE p.id=?", (rs, i) -> {
      boolean own = rs.getLong("user_id") == actor.id();
      return new Post(rs.getLong("id"), rs.getString("title"), rs.getString("body"),
          rs.getString("author_name"), rs.getObject("created_at", LocalDateTime.class),
          rs.getObject("updated_at", LocalDateTime.class), rs.getLong("version"),
          own, own || user.getRole() == Role.ADMIN);
    }, id).stream().findFirst().orElseThrow(DomainException::missing);
  }

  private LocalDateTime now() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }

  private void validate(String title, String body) {
    if (title == null || title.isBlank() || title.length() > 100
        || body == null || body.isBlank() || body.length() > 5000)
      throw DomainException.bad("INVALID_POST", "제목은 1~100자, 내용은 1~5,000자로 입력해 주세요.");
  }

  private void checkVersion(Post post, Long expected) {
    if (expected == null || expected != post.version())
      throw DomainException.conflict("STALE_VERSION", "글이 변경되었습니다. 다시 열어 확인해 주세요.");
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Post create(VillaPrincipal actor, Create input) {
    guard.lock();
    guard.actor(actor, false);
    validate(input.title(), input.body());
    var key = new GeneratedKeyHolder();
    var timestamp = now();
    jdbc.update(connection -> {
      var st = connection.prepareStatement(
          "INSERT INTO board_posts(user_id,title,body,created_at,updated_at) VALUES(?,?,?,?,?)",
          new String[] {"id"});
      st.setLong(1, actor.id());
      st.setString(2, input.title().strip());
      st.setString(3, input.body().strip());
      st.setObject(4, timestamp);
      st.setObject(5, timestamp);
      return st;
    }, key);
    return detail(actor, Objects.requireNonNull(key.getKey()).longValue());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Post update(VillaPrincipal actor, long id, Update input) {
    guard.lock();
    var post = detail(actor, id);
    if (!post.editable()) throw DomainException.forbidden();
    checkVersion(post, input.expectedVersion());
    validate(input.title(), input.body());
    jdbc.update("UPDATE board_posts SET title=?,body=?,version=version+1,updated_at=? WHERE id=?",
        input.title().strip(), input.body().strip(), now(), id);
    return detail(actor, id);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void delete(VillaPrincipal actor, long id, long expectedVersion) {
    guard.lock();
    var post = detail(actor, id);
    if (!post.deletable()) throw DomainException.forbidden();
    checkVersion(post, expectedVersion);
    jdbc.update("DELETE FROM board_posts WHERE id=?", id);
    if (!post.editable()) audit.record(actor.id(), "BOARD_DELETE", id, "관리자가 게시글 삭제");
  }
}
