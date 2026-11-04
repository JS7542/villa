package com.jinsu.villa.common.config;

import com.jinsu.villa.auth.service.*;
import com.jinsu.villa.user.repository.UserRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.session.*;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration
public class SecurityConfig {
  @Bean
  SecurityContextRepository contexts() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  CsrfTokenRepository csrfTokens() {
    return new HttpSessionCsrfTokenRepository();
  }

  @Bean
  SessionAuthenticationStrategy sessionStrategy(CsrfTokenRepository tokens) {
    return new CompositeSessionAuthenticationStrategy(
        List.of(
            new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(tokens)));
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity http,
      UserRepository users,
      Clock clock,
      SecurityContextRepository contexts,
      CsrfTokenRepository tokens)
      throws Exception {
    RequestMatcher html =
        r ->
            "GET".equals(r.getMethod())
                && ("/".equals(r.getServletPath()) || "/manage".equals(r.getServletPath())
                    || "/board".equals(r.getServletPath()));
    http.securityContext(s -> s.securityContextRepository(contexts))
        .csrf(c -> c.csrfTokenRepository(tokens))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/login",
                        "/signup",
                        "/reset",
                        "/auth/login",
                        "/auth/signup",
                        "/auth/password/reset",
                        "/assets/**",
                        "/health",
                        "/health/ready",
                        "/error")
                    .permitAll()
                    .requestMatchers("/admin/**", "/manage")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, x) -> {
                          if (html.matches(req)) {
                            res.sendRedirect("/login");
                          } else {
                            com.jinsu.villa.common.exception.SecurityErrors.write(
                                res, 401, "UNAUTHENTICATED", "다시 로그인해 주세요.");
                          }
                        })
                    .accessDeniedHandler(
                        (req, res, x) ->
                            com.jinsu.villa.common.exception.SecurityErrors.write(
                                res, 403, "FORBIDDEN", "권한 또는 요청 보안 토큰을 확인해 주세요.")))
        .headers(
            h ->
                h.contentSecurityPolicy(
                    c ->
                        c.policyDirectives(
                            "default-src 'self'; script-src 'self'; style-src 'self'; img-src"
                                + " 'self' data:; frame-ancestors 'none'; form-action 'self';"
                                + " base-uri 'self'")))
        .logout(
            l ->
                l.logoutUrl("/auth/logout")
                    .invalidateHttpSession(true)
                    .deleteCookies("JSESSIONID")
                    .logoutSuccessHandler((req, res, a) -> res.setStatus(204)))
        .addFilterAfter(new SessionValidityFilter(users, clock), SecurityContextHolderFilter.class)
        .addFilterBefore(new AuthRateLimitFilter(clock), CsrfFilter.class);
    return http.build();
  }
}
