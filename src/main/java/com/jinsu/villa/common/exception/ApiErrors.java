package com.jinsu.villa.common.exception;

import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class ApiErrors {
  public record ErrorBody(
      String code, String message, String traceId, Map<String, String> fieldErrors) {}

  private ResponseEntity<ErrorBody> response(
      HttpStatus status, String code, String message, Map<String, String> fields) {
    return ResponseEntity.status(status)
        .body(new ErrorBody(code, message, UUID.randomUUID().toString(), fields));
  }

  @ExceptionHandler(DomainException.class)
  ResponseEntity<ErrorBody> domain(DomainException e) {
    return response(e.status(), e.code(), e.getMessage(), Map.of());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ErrorBody> invalid(MethodArgumentNotValidException e) {
    Map<String, String> errors = new LinkedHashMap<>();
    e.getBindingResult()
        .getFieldErrors()
        .forEach(f -> errors.putIfAbsent(f.getField(), f.getDefaultMessage()));
    return response(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "입력 내용을 확인해 주세요.", errors);
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingRequestHeaderException.class,
    MissingServletRequestParameterException.class
  })
  ResponseEntity<ErrorBody> malformed(Exception e) {
    return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 형식이 올바르지 않습니다.", Map.of());
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ErrorBody> duplicate(DataIntegrityViolationException e) {
    return response(
        HttpStatus.CONFLICT, "DATA_CONFLICT", "이미 처리되었거나 다른 예약과 겹칩니다. 최신 상태를 확인해 주세요.", Map.of());
  }

  @ExceptionHandler({TransientDataAccessException.class, DataAccessResourceFailureException.class})
  ResponseEntity<ErrorBody> temporary(Exception e) {
    return response(
        HttpStatus.SERVICE_UNAVAILABLE,
        "TEMPORARY_FAILURE",
        "일시적인 오류입니다. 내 예약을 확인한 뒤 다시 시도해 주세요.",
        Map.of());
  }
}
