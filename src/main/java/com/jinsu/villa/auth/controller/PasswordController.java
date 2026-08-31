package com.jinsu.villa.auth.controller;

import com.jinsu.villa.auth.principal.VillaPrincipal;
import com.jinsu.villa.auth.service.PasswordService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class PasswordController {
  private final PasswordService service;

  public record Reason(@NotBlank @Size(max = 500) String reason) {}

  public record Reset(
      @NotBlank @Size(max = 100) String token, @NotBlank @Size(max = 72) String password) {}

  public record Change(
      @NotBlank @Size(max = 255) String currentPassword,
      @NotBlank @Size(max = 72) String password) {}

  @PostMapping("/admin/users/{id}/password-reset")
  public Map<String, String> issue(
      @AuthenticationPrincipal VillaPrincipal p,
      @PathVariable long id,
      @Valid @RequestBody Reason input) {
    return Map.of("token", service.issue(p, id, input.reason()));
  }

  @PostMapping("/auth/password/reset")
  public void reset(@Valid @RequestBody Reset input) {
    service.reset(input.token(), input.password());
  }

  @PostMapping("/users/me/password")
  public void change(@AuthenticationPrincipal VillaPrincipal p, @Valid @RequestBody Change input) {
    service.change(p, input.currentPassword(), input.password());
  }
}
