package com.jinsu.villa.admin.controller;

import com.jinsu.villa.admin.dto.request.UserApprovalRequest;
import com.jinsu.villa.admin.dto.request.UserRoleRequest;
import com.jinsu.villa.admin.dto.response.PendingUserResponse;
import com.jinsu.villa.admin.service.AdminService;
import com.jinsu.villa.auth.principal.VillaPrincipal;
import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin")
public class AdminController {
  private final AdminService service;

  @PatchMapping("/users/{id}/status")
  public void status(
      @AuthenticationPrincipal VillaPrincipal p,
      @PathVariable Long id,
      @Valid @RequestBody UserApprovalRequest input) {
    service.changeUserStatus(p, id, input);
  }

  @GetMapping("/users/pending")
  public List<PendingUserResponse> pending() {
    return service.getPendingUsers();
  }

  @PatchMapping("/users/{id}/role")
  public void role(
      @AuthenticationPrincipal VillaPrincipal p,
      @PathVariable Long id,
      @Valid @RequestBody UserRoleRequest input) {
    service.changeUserRole(p, id, input);
  }

  @GetMapping("/users")
  public List<Map<String, Object>> users() {
    return service.allUsers();
  }

  @GetMapping("/audit")
  public List<Map<String, Object>> audit() {
    return service.audit();
  }
}
