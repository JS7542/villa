package com.jinsu.villa.auth.service;

import com.jinsu.villa.auth.dto.request.*;
import com.jinsu.villa.auth.principal.VillaPrincipal;
import com.jinsu.villa.common.exception.DomainException;
import com.jinsu.villa.common.util.*;
import com.jinsu.villa.invite.enumtype.InviteCodeStatus;
import com.jinsu.villa.invite.repository.InviteCodeRepository;
import com.jinsu.villa.user.entity.User;
import com.jinsu.villa.user.enumtype.*;
import com.jinsu.villa.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {
  private final UserRepository users;
  private final InviteCodeRepository invites;
  private final PasswordEncoder encoder;
  private final WriteGuard guard;
  private final Clock clock;

  public static String normalize(String id) {
    return id.strip().toLowerCase(java.util.Locale.ROOT);
  }

  public static void validatePassword(String password) {
    if (password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw DomainException.bad("PASSWORD_POLICY", "비밀번호는 12자 이상, UTF-8 기준 72바이트 이하여야 합니다.");
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void signup(SignupRequest request) {
    validatePassword(request.getPassword());
    String encoded = encoder.encode(request.getPassword());
    guard.lock();
    String login = normalize(request.getLoginId());
    if (!login.matches("[a-z0-9._-]{3,50}"))
      throw DomainException.bad("LOGIN_ID_POLICY", "아이디는 영문 소문자·숫자·점·밑줄·하이픈 3~50자입니다.");
    if (users.existsByLoginId(login))
      throw DomainException.conflict("LOGIN_ID_TAKEN", "사용할 수 없는 아이디입니다.");
    var invite =
        invites
            .findByCode(Tokens.hash(request.getInviteCode().strip()))
            .orElseThrow(() -> DomainException.bad("INVALID_INVITE", "유효하지 않은 초대코드입니다."));
    if (invite.getStatus() != InviteCodeStatus.ACTIVE
        || !LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
            .isBefore(invite.getExpiresAt()))
      throw DomainException.bad("INVALID_INVITE", "유효하지 않은 초대코드입니다.");
    User user =
        users.saveAndFlush(
            User.builder()
                .loginId(login)
                .password(encoded)
                .name(request.getName().strip())
                .role(Role.USER)
                .status(UserStatus.PENDING)
                .signupNote(request.getSignupNote())
                .build());
    invite.markAsUsed(user.getId());
  }

  public VillaPrincipal authenticate(LoginRequest request) {
    var user = users.findByLoginId(normalize(request.getLoginId())).orElse(null);
    // Valid fixed bcrypt hash makes unknown-user authentication do the same expensive check.
    String hash =
        user == null
            ? "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
            : user.getPassword();
    boolean valid =
        request.getPassword().getBytes(StandardCharsets.UTF_8).length <= 72
            && encoder.matches(request.getPassword(), hash);
    if (user == null || !valid)
      throw new DomainException(
          org.springframework.http.HttpStatus.UNAUTHORIZED,
          "BAD_CREDENTIALS",
          "아이디 또는 비밀번호가 올바르지 않습니다.");
    if (user.getStatus() != UserStatus.ACTIVE)
      throw new DomainException(
          org.springframework.http.HttpStatus.FORBIDDEN,
          "ACCOUNT_INACTIVE",
          "관리자 승인 또는 계정 상태 확인이 필요합니다.");
    return VillaPrincipal.from(user, clock.instant());
  }
}
