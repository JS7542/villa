package com.jinsu.villa.board;

import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;

public final class BoardDtos {
  private BoardDtos() {}

  public record Create(
      @NotBlank(message = "제목을 입력해 주세요.")
      @Size(max = 100, message = "제목은 100자까지 쓸 수 있습니다.") String title,
      @NotBlank(message = "내용을 입력해 주세요.")
      @Size(max = 5000, message = "내용은 5,000자까지 쓸 수 있습니다.") String body) {}

  public record Update(
      @NotBlank(message = "제목을 입력해 주세요.")
      @Size(max = 100, message = "제목은 100자까지 쓸 수 있습니다.") String title,
      @NotBlank(message = "내용을 입력해 주세요.")
      @Size(max = 5000, message = "내용은 5,000자까지 쓸 수 있습니다.") String body,
      @NotNull @PositiveOrZero Long expectedVersion) {}

  public record Summary(long id, String title, String authorName, LocalDateTime createdAt) {}

  public record Post(long id, String title, String body, String authorName,
      LocalDateTime createdAt, LocalDateTime updatedAt, long version,
      boolean editable, boolean deletable) {}

  public record Page(List<Summary> items, boolean hasNext) {}
}
