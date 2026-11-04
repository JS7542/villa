package com.jinsu.villa.common.exception;

import org.springframework.http.HttpStatus;

public class DomainException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  public DomainException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static DomainException bad(String code, String message) {
    return new DomainException(HttpStatus.BAD_REQUEST, code, message);
  }

  public static DomainException conflict(String code, String message) {
    return new DomainException(HttpStatus.CONFLICT, code, message);
  }

  public static DomainException missing() {
    return new DomainException(HttpStatus.NOT_FOUND, "NOT_FOUND", "정보를 찾을 수 없습니다.");
  }

  public static DomainException unauthorized() {
    return new DomainException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "다시 로그인해 주세요.");
  }

  public static DomainException forbidden() {
    return new DomainException(HttpStatus.FORBIDDEN, "FORBIDDEN", "권한이 없습니다.");
  }
}
