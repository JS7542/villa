package com.jinsu.villa.user.service;

import com.jinsu.villa.auth.principal.VillaPrincipal;
import com.jinsu.villa.common.exception.DomainException;
import com.jinsu.villa.user.dto.response.MyInfoResponse;
import com.jinsu.villa.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {
  private final UserRepository users;

  public MyInfoResponse getMyInfo(Authentication auth) {
    var principal = (VillaPrincipal) auth.getPrincipal();
    return MyInfoResponse.from(
        users.findById(principal.id()).orElseThrow(DomainException::unauthorized));
  }
}
