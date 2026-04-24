package com.jinsu.villa.user.dto.response;

import com.jinsu.villa.user.entity.User;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class MyInfoResponse {

    private Long id;
    private String loginId;
    private String name;
    private String role;
    private String status;
    private String signupNote;

    public static MyInfoResponse from(User user) {
        return new MyInfoResponse(
                user.getId(),
                user.getLoginId(),
                user.getName(),
                user.getRole().name(),
                user.getStatus().name(),
                user.getSignupNote()
        );
    }
}