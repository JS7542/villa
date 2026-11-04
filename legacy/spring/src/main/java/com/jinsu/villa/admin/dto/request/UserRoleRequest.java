package com.jinsu.villa.admin.dto.request;

import com.jinsu.villa.user.enumtype.Role;
import jakarta.validation.constraints.*;

public record UserRoleRequest(@NotNull Role role, @NotBlank @Size(max = 500) String reason) {}
