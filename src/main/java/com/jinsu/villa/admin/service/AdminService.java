package com.jinsu.villa.admin.service;

import com.jinsu.villa.admin.dto.request.UserApprovalRequest;
import com.jinsu.villa.admin.dto.response.PendingUserResponse;
import com.jinsu.villa.auth.principal.VillaPrincipal;
import com.jinsu.villa.common.exception.DomainException;
import com.jinsu.villa.common.util.*;
import com.jinsu.villa.user.enumtype.*;
import com.jinsu.villa.user.repository.UserRepository;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminService {
  private final UserRepository users;
  private final WriteGuard guard;
  private final Audit audit;
  private final JdbcTemplate jdbc;

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void changeUserStatus(VillaPrincipal actor, Long id, UserApprovalRequest request) {
    guard.lock();
    guard.actor(actor, true);
    var user = users.findById(id).orElseThrow(DomainException::missing);
    var before = user.getStatus();
    var after = request.status();
    if (before == after) return;
    boolean allowed =
        (before == UserStatus.PENDING
                && (after == UserStatus.ACTIVE || after == UserStatus.REJECTED))
            || (before == UserStatus.ACTIVE
                && (after == UserStatus.SUSPENDED || after == UserStatus.WITHDRAWN))
            || (before == UserStatus.SUSPENDED
                && (after == UserStatus.ACTIVE || after == UserStatus.WITHDRAWN));
    if (!allowed)
      throw DomainException.conflict("INVALID_STATUS_TRANSITION", "허용하지 않는 계정 상태 변경입니다.");
    if (user.getRole() == Role.ADMIN
        && before == UserStatus.ACTIVE
        && jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE role='ADMIN' AND status='ACTIVE'", Integer.class)
            <= 1) throw DomainException.conflict("LAST_ADMIN", "마지막 관리자를 비활성화할 수 없습니다.");
    user.changeStatus(after);
    audit.record(actor.id(), "USER_STATUS", id, before + " → " + after + ": " + request.reason());
  }

  public List<PendingUserResponse> getPendingUsers() {
    return users.findAllByStatus(UserStatus.PENDING).stream()
        .map(PendingUserResponse::from)
        .toList();
  }

  public List<Map<String, Object>> allUsers() {
    return jdbc.queryForList(
        "SELECT id,login_id AS loginId,name,role,status FROM users ORDER BY id DESC LIMIT 500");
  }

  public List<Map<String, Object>> audit() {
    return jdbc.queryForList(
        "SELECT id,actor_id,action,target_id,reason,created_at FROM admin_audit ORDER BY id DESC"
            + " LIMIT 200");
  }
}
