package com.jinsu.villa.admin.dto.request;

import com.jinsu.villa.user.enumtype.UserStatus;
import jakarta.validation.constraints.*;

public record UserApprovalRequest(
    @NotNull UserStatus status, @NotBlank @Size(max = 500) String reason) {}
