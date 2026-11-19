package com.jinsu.villa.reservation.dto;

import jakarta.validation.constraints.*;
import java.time.*;

public final class BookingDtos {
  private BookingDtos() {}

  public record Create(
      @NotNull LocalDate startDate,
      @NotNull LocalDate endDate,
      @Min(1) int guestCount,
      @Size(max = 500) String memo) {}

  public record Change(
      @NotNull LocalDate startDate,
      @NotNull LocalDate endDate,
      @Min(1) int guestCount,
      @NotNull @PositiveOrZero Long expectedVersion,
      @NotBlank @Size(max = 500) String reason) {}

  public record Cancel(
      @NotNull @PositiveOrZero Long expectedVersion, @NotBlank @Size(max = 500) String reason) {}

  public record Block(
      @NotNull LocalDate startDate,
      @NotNull LocalDate endDate,
      @NotBlank @Size(max = 500) String reason) {}

  public record Booking(
      long id,
      long userId,
      String userName,
      LocalDate startDate,
      LocalDate endDate,
      int guestCount,
      String memo,
      String status,
      long version) {}

  public record CalendarItem(
      long id,
      String kind,
      String userName,
      LocalDate startDate,
      LocalDate endDate,
      boolean mine) {}

  public record History(
      long id,
      String action,
      String reason,
      String beforeValue,
      String afterValue,
      LocalDateTime createdAt) {}

  public record BlockView(
      long id,
      LocalDate startDate,
      LocalDate endDate,
      String reason,
      String status,
      long version) {}

  public record Notice(
      long id,
      long reservationId,
      String userName,
      String action,
      String reason,
      String status,
      LocalDateTime createdAt) {}

  public record Policy(
      int advanceDays,
      int maxDays,
      int maxActive,
      int maxGuests,
      LocalDate today,
      String timezone,
      String dateMeaning) {}
}
