package com.jinsu.villa.invite.controller;

import com.jinsu.villa.auth.principal.VillaPrincipal;
import com.jinsu.villa.invite.service.InviteCodeService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/invite-codes")
public class InviteCodeController {
  private final InviteCodeService service;

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public InviteCodeService.Issued issue(@AuthenticationPrincipal VillaPrincipal p) {
    return service.issue(p);
  }

  @GetMapping
  public List<InviteCodeService.View> list() {
    return service.list();
  }

  @PatchMapping("/{id}/revoke")
  public void revoke(@AuthenticationPrincipal VillaPrincipal p, @PathVariable long id) {
    service.revoke(p, id);
  }
}
