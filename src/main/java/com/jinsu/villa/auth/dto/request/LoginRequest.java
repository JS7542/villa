package com.jinsu.villa.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class LoginRequest {

  @NotBlank(message = "아이디는 필수입니다.")
  @Size(max = 50, message = "아이디는 50자 이하여야 합니다.")
  private String loginId;

  @NotBlank(message = "비밀번호는 필수입니다.")
  @Size(max = 255, message = "비밀번호는 255자 이하여야 합니다.")
  private String password;
}
