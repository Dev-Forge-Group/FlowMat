package org.myweb.flowmat.domain.user.domain.entity;

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
@Table(name = "user_roles")
public class UserRole {

    @Id
    private UUID userRolesId;

    private UUID userId;
    private UUID roleId;
    private String scopeType;
    private UUID scopeId;
    private UUID grantedBy;
    private OffsetDateTime grantedAt;
}
