package org.myweb.flowmat.domain.project.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** Membership of an organization (docs/domain/organization.md OR1); it does not reach any project's data. */
@Getter
@Setter
@Entity
@Table(name = "organization_member")
public class OrganizationMember {
    public static final String OWNER = "owner";
    public static final String ADMIN = "admin";
    public static final String MEMBER = "member";
    public static final String ACTIVE = "active";

    @Id private String organizationMemberId;
    private String organizationId;
    private String userId;
    private String orgRole;
    /** active, left or removed. */
    private String memberStatus;
    private OffsetDateTime joinedAt;
    private OffsetDateTime leftAt;
}
