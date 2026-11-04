package com.jinsu.villa.auth.service;

import com.jinsu.villa.auth.principal.VillaPrincipal;
import com.jinsu.villa.common.exception.DomainException;
import com.jinsu.villa.common.util.*;
import com.jinsu.villa.user.enumtype.UserStatus;
import com.jinsu.villa.user.repository.UserRepository;
import java.time.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class PasswordService {
  private final WriteGuard guard;
  private final UserRepository users;
  private final JdbcTemplate jdbc;
  private final PasswordEncoder encoder;
  private final Clock clock;
  private final Audit audit;

  private LocalDateTime now() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public String issue(VillaPrincipal actor, long id, String reason) {
    guard.lock();
    guard.actor(actor, true);
    var u = users.findById(id).orElseThrow(DomainException::missing);
    if (u.getStatus() != UserStatus.ACTIVE)
      throw DomainException.conflict("ACCOUNT_INACTIVE", "활성 계정만 재설정할 수 있습니다.");
    jdbc.update("DELETE FROM password_reset_tokens WHERE user_id=?", id);
    String raw = Tokens.random();
    jdbc.update(
        "INSERT INTO password_reset_tokens(user_id,token_hash,expires_at) VALUES(?,?,?)",
        id,
        Tokens.hash(raw),
        now().plusMinutes(15));
    audit.record(actor.id(), "RESET_ISSUE", id, reason);
    return raw;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void reset(String token, String password) {
    AuthService.validatePassword(password);
    String encoded = encoder.encode(password);
    guard.lock();
    var ids =
        jdbc.queryForList(
            "SELECT user_id FROM password_reset_tokens WHERE token_hash=? AND used_at IS NULL AND"
                + " expires_at>?",
            Long.class,
            Tokens.hash(token),
            now());
    if (ids.isEmpty())
      throw DomainException.bad("INVALID_RESET_TOKEN", "재설정 코드가 유효하지 않거나 만료되었습니다.");
    var u = users.findById(ids.getFirst()).orElseThrow(DomainException::missing);
    if (u.getStatus() != UserStatus.ACTIVE) throw DomainException.forbidden();
    u.changePassword(encoded);
    jdbc.update(
        "UPDATE password_reset_tokens SET used_at=? WHERE user_id=? AND used_at IS NULL",
        now(),
        u.getId());
    audit.record(u.getId(), "PASSWORD_RESET", u.getId(), "본인 비밀번호 재설정 완료");
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void change(VillaPrincipal actor, String current, String next) {
    AuthService.validatePassword(next);
    guard.lock();
    var u = guard.actor(actor, false);
    if (current.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72
        || !encoder.matches(current, u.getPassword()))
      throw DomainException.bad("BAD_PASSWORD", "현재 비밀번호가 올바르지 않습니다.");
    u.changePassword(encoder.encode(next));
    jdbc.update("DELETE FROM password_reset_tokens WHERE user_id=?", u.getId());
    audit.record(actor.id(), "PASSWORD_CHANGE", actor.id(), "본인 비밀번호 변경");
  }
}
