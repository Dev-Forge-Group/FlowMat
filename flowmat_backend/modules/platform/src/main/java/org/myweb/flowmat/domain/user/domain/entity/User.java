package org.myweb.flowmat.domain.user.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.myweb.flowmat.global.common.BaseTimeEntity;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "users")
public class User extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private String userId;

    @Column(name = "user_name", nullable = false)
    private String userName;

    @Column(name = "user_email", nullable = false, unique = true)
    private String userEmail;

    @Column(name = "user_nickname", unique = true)
    private String userNickname;

    @Column(name = "user_pwd", nullable = false)
    private String userPwd;

    @Column(name = "user_status")
    private String userStatus;

    @Column(name = "user_role")
    private String userRole;
    
    @Column(name = "user_birth")
    private LocalDate userBirth;

    @Column(name = "user_tel")
    private String userTel;

    @Column(name = "lastlogin_at")
    private OffsetDateTime lastLoginAt;

    @Column(name = "pwd_updated_at")
    private OffsetDateTime pwdUpdatedAt;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "delete_yn")
    private String deleteYn;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "dormant_at")
    private OffsetDateTime dormantAt;

    @Column(name = "dormant_token")
    private String dormantToken;

    @Column(name = "withdrawn_at")
    private OffsetDateTime withdrawnAt;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "email_verified_yn")
    private String emailVerifiedYn;

    @Column(name = "email_verified_at")
    private OffsetDateTime emailVerifiedAt;

    @Column(name = "failed_login_count")
    private Integer failedLoginCount;

    @Column(name = "locked_at")
    private OffsetDateTime lockedAt;
}
