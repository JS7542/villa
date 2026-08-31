package com.jinsu.villa.reservation.controller;

import com.jinsu.villa.auth.principal.VillaPrincipal;
import com.jinsu.villa.reservation.dto.BookingDtos.*;
import com.jinsu.villa.reservation.service.ReservationService;
import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class ReservationController {
  private final ReservationService service;

  @GetMapping("/reservations/policy")
  public Policy policy() {
    return service.policy();
  }

  @PostMapping("/reservations")
  @ResponseStatus(HttpStatus.CREATED)
  public Booking create(
      @AuthenticationPrincipal VillaPrincipal p,
      @Valid @RequestBody Create input,
      @RequestHeader("Idempotency-Key") String key) {
    return service.create(p, input, key);
  }

  @GetMapping("/reservations/me")
  public List<Booking> mine(@AuthenticationPrincipal VillaPrincipal p) {
    return service.mine(p);
  }

  @GetMapping("/reservations/calendar")
  public List<CalendarItem> calendar(
      @AuthenticationPrincipal VillaPrincipal p, @RequestParam int year, @RequestParam int month) {
    return service.calendar(p, year, month);
  }

  @GetMapping("/reservations/{id}")
  public Booking detail(@AuthenticationPrincipal VillaPrincipal p, @PathVariable long id) {
    return service.detail(p, id);
  }

  @GetMapping("/reservations/{id}/history")
  public List<History> history(@AuthenticationPrincipal VillaPrincipal p, @PathVariable long id) {
    return service.history(p, id);
  }

  @DeleteMapping("/reservations/{id}")
  public Booking cancel(@AuthenticationPrincipal VillaPrincipal p, @PathVariable long id) {
    return service.cancel(p, id);
  }

  @GetMapping("/admin/reservations")
  public List<Booking> all() {
    return service.all();
  }

  @PatchMapping("/admin/reservations/{id}")
  public Booking change(
      @AuthenticationPrincipal VillaPrincipal p,
      @PathVariable long id,
      @Valid @RequestBody Change input) {
    return service.change(p, id, input);
  }

  @PatchMapping("/admin/reservations/{id}/cancel")
  public Booking forceCancel(
      @AuthenticationPrincipal VillaPrincipal p,
      @PathVariable long id,
      @Valid @RequestBody Cancel input) {
    return service.forceCancel(p, id, input);
  }

  @GetMapping("/admin/calendar-blocks")
  public List<BlockView> blocks() {
    return service.blocks();
  }

  @PostMapping("/admin/calendar-blocks")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Long> block(
      @AuthenticationPrincipal VillaPrincipal p, @Valid @RequestBody Block input) {
    return Map.of("id", service.block(p, input));
  }

  @PatchMapping("/admin/calendar-blocks/{id}/release")
  public void release(
      @AuthenticationPrincipal VillaPrincipal p,
      @PathVariable long id,
      @Valid @RequestBody Cancel input) {
    service.release(p, id, input);
  }

  @GetMapping("/users/me/notices")
  public List<Notice> notices(@AuthenticationPrincipal VillaPrincipal p) {
    return service.notices(p, false);
  }

  @GetMapping("/admin/communications")
  public List<Notice> adminNotices(@AuthenticationPrincipal VillaPrincipal p) {
    return service.notices(p, true);
  }

  @PatchMapping("/admin/communications/{id}/contact")
  public void contact(@AuthenticationPrincipal VillaPrincipal p, @PathVariable long id) {
    service.contact(p, id);
  }

  @PatchMapping("/users/me/notices/{id}/acknowledge")
  public void acknowledge(@AuthenticationPrincipal VillaPrincipal p, @PathVariable long id) {
    service.acknowledge(p, id);
  }
}
