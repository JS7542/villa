package com.jinsu.villa.invite.entity;

import com.jinsu.villa.common.entity.BaseTimeEntity;
import com.jinsu.villa.invite.enumtype.InviteCodeStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "invite_codes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InviteCode extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InviteCodeStatus status;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used_by_user_id")
    private Long usedByUserId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Builder
    public InviteCode(String code, InviteCodeStatus status, LocalDateTime expiresAt, Long usedByUserId, Long createdBy) {
        this.code = code;
        this.status = status;
        this.expiresAt = expiresAt;
        this.usedByUserId = usedByUserId;
        this.createdBy = createdBy;
    }
    public void markAsUsed(Long usedByUserId) {
        this.status = InviteCodeStatus.USED;
        this.usedByUserId = usedByUserId;
    }
    public void expire() {
    this.status = InviteCodeStatus.EXPIRED;
}
}