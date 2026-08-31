package com.jinsu.villa.common.util;

import com.jinsu.villa.auth.principal.VillaPrincipal;
import com.jinsu.villa.common.exception.DomainException;
import com.jinsu.villa.user.entity.User;
import com.jinsu.villa.user.enumtype.*;
import com.jinsu.villa.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
public class WriteGuard {
  private final JdbcTemplate jdbc;
  private final UserRepository users;

  public void lock() {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Write guard requires a transaction");
    jdbc.queryForObject("SELECT id FROM villa_schedule_lock WHERE id=1 FOR UPDATE", Long.class);
  }

  public User actor(VillaPrincipal actor, boolean admin) {
    if (actor == null) throw DomainException.unauthorized();
    User u = users.findById(actor.id()).orElseThrow(DomainException::unauthorized);
    if (u.getStatus() != UserStatus.ACTIVE || u.getAuthVersion() != actor.authVersion())
      throw DomainException.unauthorized();
    if (admin && u.getRole() != Role.ADMIN) throw DomainException.forbidden();
    return u;
  }
}
