package com.jinsu.villa.auth.principal;

import com.jinsu.villa.user.entity.User;
import com.jinsu.villa.user.enumtype.Role;
import java.io.Serializable;
import java.time.Instant;

public record VillaPrincipal(
    Long id, String name, Role role, long authVersion, Instant authenticatedAt)
    implements Serializable {
  public static VillaPrincipal from(User u, Instant now) {
    return new VillaPrincipal(u.getId(), u.getName(), u.getRole(), u.getAuthVersion(), now);
  }
}
