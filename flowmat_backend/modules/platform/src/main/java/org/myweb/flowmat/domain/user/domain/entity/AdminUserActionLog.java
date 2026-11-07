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
@Table(name = "admin_user_action_log")
public class AdminUserActionLog {

    @Id
    @Column(name = "action_log_id")
    private UUID actionLogId;

    @Column(name = "actor_user_id", nullable = false)
    private String actorUserId;

    @Column(name = "target_user_id", nullable = false)
    private String targetUserId;

    @Column(name = "action_type", nullable = false)
    private String actionType;

    @Column(name = "previous_value")
    private String previousValue;

    @Column(name = "new_value")
    private String newValue;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
