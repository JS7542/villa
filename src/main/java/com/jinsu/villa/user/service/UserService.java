package com.jinsu.villa.user.service;

import com.jinsu.villa.user.dto.response.MyInfoResponse;
import com.jinsu.villa.user.entity.User;
import com.jinsu.villa.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public MyInfoResponse getMyInfo(Authentication authentication) {
        String loginId = (String) authentication.getPrincipal();

        User user = userRepository.findByLoginId(loginId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        return MyInfoResponse.from(user);
    }
}