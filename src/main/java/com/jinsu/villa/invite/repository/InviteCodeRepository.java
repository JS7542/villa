package com.jinsu.villa.invite.repository;

import com.jinsu.villa.invite.entity.InviteCode;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InviteCodeRepository extends JpaRepository<InviteCode, Long> {

  Optional<InviteCode> findByCode(String code);

  boolean existsByCode(String code);
}
