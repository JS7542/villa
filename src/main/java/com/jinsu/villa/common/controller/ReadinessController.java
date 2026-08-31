package com.jinsu.villa.common.controller;

import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ReadinessController {
  private final DataSource dataSource;

  @GetMapping("/health/ready")
  public ResponseEntity<Map<String, String>> ready() {
    // Query an application table as well as testing connectivity and runtime permissions.
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement("SELECT id FROM villa_schedule_lock WHERE id=1")) {
      statement.setQueryTimeout(2);
      try (var result = statement.executeQuery()) {
        if (result.next()) return ResponseEntity.ok(Map.of("status", "UP"));
      }
    } catch (SQLException ignored) {
      // Never expose connection URLs, credentials or driver error messages publicly.
    }
    return ResponseEntity.status(503).body(Map.of("status", "DOWN"));
  }
}
