package com.jinsu.villa.admin.dto.request;

import com.jinsu.villa.user.enumtype.UserStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class UserApprovalRequest {

    @NotNull(message = "status는 필수입니다.")
    private UserStatus status;
}