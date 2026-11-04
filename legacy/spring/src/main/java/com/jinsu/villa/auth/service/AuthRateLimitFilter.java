package com.jinsu.villa.auth.service;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import org.springframework.web.filter.OncePerRequestFilter;

public class AuthRateLimitFilter extends OncePerRequestFilter {
  private record Window(long start, int count) {}

  private final Map<String, Window> windows = new HashMap<>();
  private final Clock clock;

  public AuthRateLimitFilter(Clock clock) {
    this.clock = clock;
  }

  private synchronized boolean accept(String key) {
    long now = clock.millis();
    windows.entrySet().removeIf(e -> now - e.getValue().start() >= 60000);
    Window old = windows.get(key);
    if (old == null && windows.size() >= 10000) return false;
    if (old != null && old.count() >= 30) return false;
    windows.put(
        key, new Window(old == null ? now : old.start(), old == null ? 1 : old.count() + 1));
    return true;
  }

  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    if ("POST".equals(req.getMethod())
        && Set.of("/auth/login", "/auth/signup", "/auth/password/reset")
            .contains(req.getServletPath())
        && !accept(req.getRemoteAddr())) {
      res.setHeader("Retry-After", "60");
      com.jinsu.villa.common.exception.SecurityErrors.write(
          res, 429, "RATE_LIMITED", "잠시 후 다시 시도해 주세요.");
      return;
    }
    chain.doFilter(req, res);
  }
}
