package com.jinsu.villa.admin.dto.response;

import com.jinsu.villa.user.entity.User;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class PendingUserResponse {

    private Long id;
    private String loginId;
    private String name;
    private String signupNote;
    private String status;

    public static PendingUserResponse from(User user) {
        return new PendingUserResponse(
                user.getId(),
                user.getLoginId(),
                user.getName(),
                user.getSignupNote(),
                user.getStatus().name()
        );
    }
}