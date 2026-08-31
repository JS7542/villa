package com.jinsu.villa.auth.controller;

import com.jinsu.villa.auth.dto.request.*;
import com.jinsu.villa.auth.principal.VillaPrincipal;
import com.jinsu.villa.auth.service.AuthService;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class AuthController {
  private final AuthService auth;
  private final SecurityContextRepository contexts;
  private final SessionAuthenticationStrategy sessionStrategy;

  @PostMapping("/signup")
  @ResponseStatus(HttpStatus.CREATED)
  public void signup(@Valid @RequestBody SignupRequest request) {
    auth.signup(request);
  }

  @PostMapping("/login")
  public VillaPrincipal login(
      @Valid @RequestBody LoginRequest input,
      HttpServletRequest request,
      HttpServletResponse response) {
    var user = auth.authenticate(input);
    var authentication =
        UsernamePasswordAuthenticationToken.authenticated(
            user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
    request.getSession();
    sessionStrategy.onAuthentication(authentication, request, response);
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    contexts.saveContext(context, request, response);
    return user;
  }
}
