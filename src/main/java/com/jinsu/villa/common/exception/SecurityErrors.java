package com.jinsu.villa.common.exception;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

public final class SecurityErrors {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  public static void write(HttpServletResponse res, int status, String code, String message)
      throws IOException {
    res.setStatus(status);
    res.setContentType("application/json;charset=UTF-8");
    res.getWriter()
        .write(
            JSON.writeValueAsString(
                Map.of(
                    "code",
                    code,
                    "message",
                    message,
                    "traceId",
                    UUID.randomUUID().toString(),
                    "fieldErrors",
                    Map.of())));
  }
}
