package com.jinsu.villa.invite.service;

import com.jinsu.villa.auth.principal.VillaPrincipal;
import com.jinsu.villa.common.exception.DomainException;
import com.jinsu.villa.common.util.*;
import com.jinsu.villa.invite.entity.InviteCode;
import com.jinsu.villa.invite.enumtype.InviteCodeStatus;
import com.jinsu.villa.invite.repository.InviteCodeRepository;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InviteCodeService {
  private final InviteCodeRepository invites;
  private final WriteGuard guard;
  private final Audit audit;
  private final Clock clock;

  public record Issued(long id, String code, LocalDateTime expiresAt) {}

  public record View(long id, String status, LocalDateTime expiresAt, Long usedByUserId) {}

  private LocalDateTime now() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Issued issue(VillaPrincipal actor) {
    guard.lock();
    guard.actor(actor, true);
    String raw = Tokens.random();
    var expiry = now().plusDays(7);
    var invite =
        invites.saveAndFlush(
            InviteCode.builder()
                .code(Tokens.hash(raw))
                .status(InviteCodeStatus.ACTIVE)
                .expiresAt(expiry)
                .createdBy(actor.id())
                .build());
    audit.record(actor.id(), "INVITE_CREATE", invite.getId(), "7일 유효 초대 발급");
    return new Issued(invite.getId(), raw, expiry);
  }

  public List<View> list() {
    return invites
        .findAll(
            org.springframework.data.domain.PageRequest.of(
                0, 200, org.springframework.data.domain.Sort.by("id").descending()))
        .stream()
        .map(
            i ->
                new View(
                    i.getId(),
                    i.getStatus() == InviteCodeStatus.ACTIVE && !now().isBefore(i.getExpiresAt())
                        ? "EXPIRED"
                        : i.getStatus().name(),
                    i.getExpiresAt(),
                    i.getUsedByUserId()))
        .toList();
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void revoke(VillaPrincipal actor, long id) {
    guard.lock();
    guard.actor(actor, true);
    var i = invites.findById(id).orElseThrow(DomainException::missing);
    if (i.getStatus() == InviteCodeStatus.USED)
      throw DomainException.conflict("INVITE_USED", "이미 사용한 초대입니다.");
    if (i.getStatus() == InviteCodeStatus.REVOKED) return;
    i.revoke();
    audit.record(actor.id(), "INVITE_REVOKE", id, "초대 폐기");
  }
}
