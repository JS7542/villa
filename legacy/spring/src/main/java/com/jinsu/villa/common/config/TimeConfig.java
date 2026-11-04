package com.jinsu.villa.common.config;

import java.time.*;
import java.util.Optional;
import org.springframework.context.annotation.*;
import org.springframework.data.auditing.DateTimeProvider;

@Configuration
public class TimeConfig {
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public DateTimeProvider utcDateTimeProvider(Clock clock) {
    return () -> Optional.of(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
  }
}
