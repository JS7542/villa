package com.jinsu.villa.auth.service;

import com.jinsu.villa.auth.principal.VillaPrincipal;
import com.jinsu.villa.user.enumtype.UserStatus;
import com.jinsu.villa.user.repository.UserRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class SessionValidityFilter extends OncePerRequestFilter {
  private final UserRepository users;
  private final Clock clock;

  public SessionValidityFilter(UserRepository users, Clock clock) {
    this.users = users;
    this.clock = clock;
  }

  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var a = SecurityContextHolder.getContext().getAuthentication();
    if (a != null && a.getPrincipal() instanceof VillaPrincipal p) {
      var u = users.findById(p.id()).orElse(null);
      if (u == null
          || u.getStatus() != UserStatus.ACTIVE
          || u.getAuthVersion() != p.authVersion()
          || !clock.instant().isBefore(p.authenticatedAt().plus(Duration.ofHours(12)))) {
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) session.invalidate();
      }
    }
    chain.doFilter(request, response);
  }
}
