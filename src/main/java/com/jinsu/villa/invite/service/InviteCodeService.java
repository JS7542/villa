package com.jinsu.villa.invite.service;

import com.jinsu.villa.invite.entity.InviteCode;
import com.jinsu.villa.invite.enumtype.InviteCodeStatus;
import com.jinsu.villa.invite.repository.InviteCodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InviteCodeService {

    private final InviteCodeRepository inviteCodeRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireInviteCode(Long inviteCodeId) {
        InviteCode inviteCode = inviteCodeRepository.findById(inviteCodeId)
                .orElseThrow(() -> new IllegalArgumentException("초대코드를 찾을 수 없습니다."));

        if (inviteCode.getStatus() == InviteCodeStatus.ACTIVE) {
            inviteCode.expire();
        }
    }
}