package com.jinsu.villa.user.controller;

import com.jinsu.villa.user.dto.response.MyInfoResponse;
import com.jinsu.villa.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/users")
public class UserController {

  private final UserService userService;

  @GetMapping("/me")
  public MyInfoResponse getMyInfo(Authentication authentication) {
    return userService.getMyInfo(authentication);
  }
}
