package com.jinsu.villa.user.repository;

import com.jinsu.villa.user.entity.User;
import com.jinsu.villa.user.enumtype.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByLoginId(String loginId);

    Optional<User> findByLoginId(String loginId);

    List<User> findAllByStatus(UserStatus status);
}