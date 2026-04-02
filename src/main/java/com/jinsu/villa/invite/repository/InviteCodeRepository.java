package com.jinsu.villa.invite.repository;

import com.jinsu.villa.invite.entity.InviteCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InviteCodeRepository extends JpaRepository<InviteCode, Long> {

    Optional<InviteCode> findByCode(String code);

    boolean existsByCode(String code);
}