package com.jinsu.villa.admin.service;

import com.jinsu.villa.admin.dto.request.UserApprovalRequest;
import com.jinsu.villa.admin.dto.response.PendingUserResponse;
import com.jinsu.villa.user.entity.User;
import com.jinsu.villa.user.enumtype.UserStatus;
import com.jinsu.villa.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminService {

    private final UserRepository userRepository;

    @Transactional
    public void changeUserStatus(Long userId, UserApprovalRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        validateTargetStatus(request.getStatus());

        user.changeStatus(request.getStatus());
    }

    public List<PendingUserResponse> getPendingUsers() {
        return userRepository.findAllByStatus(UserStatus.PENDING).stream()
                .map(PendingUserResponse::from)
                .toList();
    }

    private void validateTargetStatus(UserStatus status) {
        if (status != UserStatus.ACTIVE && status != UserStatus.REJECTED) {
            throw new IllegalArgumentException("변경할 수 없는 상태입니다.");
        }
    }
}