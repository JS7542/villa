package com.jinsu.villa;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinsu.villa.auth.service.AuthRateLimitFilter;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class AuthRateLimitFilterTest {
  @Test
  void changingUntrustedForwardedHeadersCannotBypassAddressLimit() throws Exception {
    var filter = new AuthRateLimitFilter(Clock.systemUTC());
    for (int i = 0; i < 31; i++) {
      var request = new MockHttpServletRequest("POST", "/auth/login");
      request.setServletPath("/auth/login");
      request.setRemoteAddr("198.51.100.25");
      request.addHeader("X-Forwarded-For", "203.0.113." + i);
      var response = new MockHttpServletResponse();
      filter.doFilter(request, response, new MockFilterChain());
      assertThat(response.getStatus()).isEqualTo(i < 30 ? 200 : 429);
    }
    var other = new MockHttpServletRequest("POST", "/auth/login");
    other.setServletPath("/auth/login");
    other.setRemoteAddr("198.51.100.26");
    var response = new MockHttpServletResponse();
    filter.doFilter(other, response, new MockFilterChain());
    assertThat(response.getStatus()).isEqualTo(200);
  }
}
