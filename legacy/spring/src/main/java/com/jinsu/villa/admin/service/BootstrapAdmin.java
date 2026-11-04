package com.jinsu.villa.admin.service;

import com.jinsu.villa.auth.service.AuthService;
import com.jinsu.villa.common.util.*;
import com.jinsu.villa.user.entity.User;
import com.jinsu.villa.user.enumtype.*;
import com.jinsu.villa.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnProperty(name = "villa.bootstrap.enabled", havingValue = "true")
public class BootstrapAdmin {
  @Bean
  ApplicationRunner bootstrap(
      UserRepository users,
      PasswordEncoder encoder,
      WriteGuard guard,
      Audit audit,
      TransactionTemplate tx,
      @Value("${villa.bootstrap.login-id}") String login,
      @Value("${villa.bootstrap.password}") String password) {
    return args -> {
      String id = AuthService.normalize(login);
      if (!id.matches("[a-z0-9._-]{3,50}"))
        throw new IllegalStateException("A valid bootstrap login ID is required");
      AuthService.validatePassword(password);
      tx.executeWithoutResult(
          status -> {
            guard.lock();
            if (users.count() > 0)
              throw new IllegalStateException(
                  "Bootstrap requires an empty users table; disable bootstrap after first launch");
            var u =
                users.saveAndFlush(
                    User.builder()
                        .loginId(id)
                        .password(encoder.encode(password))
                        .name("운영 관리자")
                        .role(Role.ADMIN)
                        .status(UserStatus.ACTIVE)
                        .build());
            audit.record(u.getId(), "BOOTSTRAP", u.getId(), "최초 관리자 생성");
          });
    };
  }
}
