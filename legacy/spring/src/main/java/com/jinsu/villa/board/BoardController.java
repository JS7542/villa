package com.jinsu.villa.board;

import com.jinsu.villa.auth.principal.VillaPrincipal;
import com.jinsu.villa.board.BoardDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/board/posts")
public class BoardController {
  private final BoardService board;

  @GetMapping
  public Page list(@AuthenticationPrincipal VillaPrincipal actor,
      @RequestParam(required = false) Long before) {
    return board.list(actor, before);
  }

  @GetMapping("/{id}")
  public Post detail(@AuthenticationPrincipal VillaPrincipal actor, @PathVariable long id) {
    return board.detail(actor, id);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Post create(@AuthenticationPrincipal VillaPrincipal actor, @Valid @RequestBody Create input) {
    return board.create(actor, input);
  }

  @PatchMapping("/{id}")
  public Post update(@AuthenticationPrincipal VillaPrincipal actor, @PathVariable long id,
      @Valid @RequestBody Update input) {
    return board.update(actor, id, input);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@AuthenticationPrincipal VillaPrincipal actor, @PathVariable long id,
      @RequestParam long expectedVersion) {
    board.delete(actor, id, expectedVersion);
  }
}
