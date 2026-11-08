package org.myweb.flowmat.domain.project.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An ownership and account-group boundary above projects (ADR-001, docs/domain/organization.md OR1). It never grants
 * project access: project_member stays the source of truth.
 */
@Getter
@Setter
@Entity
@Table(name = "organization")
public class Organization {
    public static final String PERSONAL = "personal";
    public static final String TEAM = "team";

    @Id private String organizationId;
    private String organizationName;
    /** personal (one per user) or team. */
    private String organizationType;
    /** Who created it; the owners are the members with the owner role. */
    private String ownerUserId;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    @JdbcTypeCode(SqlTypes.CHAR) private String deletedYn;
}
