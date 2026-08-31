package com.jinsu.villa.user.entity;

import com.jinsu.villa.common.entity.BaseTimeEntity;
import com.jinsu.villa.user.enumtype.Role;
import com.jinsu.villa.user.enumtype.UserStatus;
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

@Getter
@Entity
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "login_id", nullable = false, unique = true, length = 50)
  private String loginId;

  @Column(nullable = false, length = 255)
  private String password;

  @Column(nullable = false, length = 30)
  private String name;

  @Enumerated(EnumType.STRING)
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.VARCHAR)
  @Column(nullable = false, length = 20)
  private Role role;

  @Enumerated(EnumType.STRING)
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.VARCHAR)
  @Column(nullable = false, length = 20)
  private UserStatus status;

  @Column(name = "signup_note", length = 500)
  private String signupNote;

  @Column(name = "auth_version", nullable = false)
  private long authVersion;

  @Builder
  public User(
      String loginId,
      String password,
      String name,
      Role role,
      UserStatus status,
      String signupNote) {
    this.loginId = loginId;
    this.password = password;
    this.name = name;
    this.role = role;
    this.status = status;
    this.signupNote = signupNote;
  }

  public void changeStatus(UserStatus status) {
    this.status = status;
    this.authVersion++;
  }

  public void changePassword(String encoded) {
    this.password = encoded;
    this.authVersion++;
  }

  public void revokeSessions() {
    this.authVersion++;
  }
}
