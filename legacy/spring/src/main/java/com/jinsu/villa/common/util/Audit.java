package com.jinsu.villa.common.util;

import java.time.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class Audit {
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public void record(Long actor, String action, Long target, String reason) {
    jdbc.update(
        "INSERT INTO admin_audit(actor_id,action,target_id,reason,created_at) VALUES(?,?,?,?,?)",
        actor,
        action,
        target,
        reason,
        LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
  }
}
