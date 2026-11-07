package org.myweb.flowmat.domain.project.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.myweb.flowmat.global.common.CreatedUpdatedAuditEntity;

@Getter
@Setter
@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name = "project")
public class Project extends CreatedUpdatedAuditEntity {

    @Id
    private String projectId;

    private String projectName;
    private String ownerId;
    private String projectDesc;
    private String projectStatus;
    private String visibility;
    private String currentWorkflowId;
    /** The organization the project belongs to (ADR-001, docs/domain/organization.md); grants no access. */
    private String organizationId;
    private String timeZone = "Asia/Seoul";
    private long timeZoneVersion;
    private String timeZoneUpdatedBy;
}
