package com.jinsu.villa.auth.service;

import com.jinsu.villa.auth.dto.request.LoginRequest;
import com.jinsu.villa.auth.dto.request.SignupRequest;
import com.jinsu.villa.auth.dto.response.LoginResponse;
import com.jinsu.villa.auth.jwt.JwtTokenProvider;
import com.jinsu.villa.invite.entity.InviteCode;
import com.jinsu.villa.invite.enumtype.InviteCodeStatus;
import com.jinsu.villa.invite.repository.InviteCodeRepository;
import com.jinsu.villa.invite.service.InviteCodeService;
import com.jinsu.villa.user.entity.User;
import com.jinsu.villa.user.enumtype.Role;
import com.jinsu.villa.user.enumtype.UserStatus;
import com.jinsu.villa.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final InviteCodeRepository inviteCodeRepository;
    private final InviteCodeService inviteCodeService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional
    public void signup(SignupRequest request) {
        validateDuplicateLoginId(request.getLoginId());

        InviteCode inviteCode = findValidInviteCode(request.getInviteCode());

        User user = User.builder()
                .loginId(request.getLoginId())
                .password(passwordEncoder.encode(request.getPassword()))
                .name(request.getName())
                .role(Role.USER)
                .status(UserStatus.PENDING)
                .signupNote(request.getSignupNote())
                .build();

        User savedUser = userRepository.save(user);

        inviteCode.markAsUsed(savedUser.getId());
    }

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByLoginId(request.getLoginId())
                .orElseThrow(() -> new IllegalArgumentException("아이디 또는 비밀번호가 올바르지 않습니다."));

        validatePassword(request.getPassword(), user.getPassword());
        validateLoginStatus(user.getStatus());

        String accessToken = jwtTokenProvider.createAccessToken(
                user.getId(),
                user.getLoginId(),
                user.getRole().name()
        );

        return new LoginResponse(accessToken);
    }

    private void validateDuplicateLoginId(String loginId) {
        if (userRepository.existsByLoginId(loginId)) {
            throw new IllegalArgumentException("이미 사용 중인 loginId입니다.");
        }
    }

    private InviteCode findValidInviteCode(String code) {
        InviteCode inviteCode = inviteCodeRepository.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 초대코드입니다."));

        if (inviteCode.getStatus() != InviteCodeStatus.ACTIVE) {
            throw new IllegalArgumentException("사용할 수 없는 초대코드입니다.");
        }

        if (inviteCode.getExpiresAt().isBefore(LocalDateTime.now())) {
            inviteCodeService.expireInviteCode(inviteCode.getId());
            throw new IllegalArgumentException("만료된 초대코드입니다.");
        }

        return inviteCode;
    }

    private void validatePassword(String rawPassword, String encodedPassword) {
        if (!passwordEncoder.matches(rawPassword, encodedPassword)) {
            throw new IllegalArgumentException("아이디 또는 비밀번호가 올바르지 않습니다.");
        }
    }

    private void validateLoginStatus(UserStatus status) {
        if (status == UserStatus.PENDING) {
            throw new IllegalArgumentException("관리자 승인 후 로그인할 수 있습니다.");
        }

        if (status == UserStatus.REJECTED) {
            throw new IllegalArgumentException("로그인이 거부된 계정입니다.");
        }
    }
}