package org.myweb.flowmat.domain.user.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "user_login_history")
public class UserLoginHistory {

    @Id
    @Column(name = "login_history_id")
    private UUID loginHistoryId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "login_ip")
    private String loginIp;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "login_result")
    private String loginResult;

    @Column(name = "fail_reason")
    private String failReason;

    @Column(name = "login_at")
    private OffsetDateTime loginAt;

    @Column(name = "logout_at")
    private OffsetDateTime logoutAt;

    @Column(name = "refresh_token_id")
    private UUID refreshTokenId;
}