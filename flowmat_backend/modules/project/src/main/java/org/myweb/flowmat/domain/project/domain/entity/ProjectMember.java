package org.myweb.flowmat.domain.project.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.myweb.flowmat.global.common.BaseTimeEntity;

@Getter
@Setter
@Entity
@Table(name = "project_member")
public class ProjectMember extends BaseTimeEntity {

    @Id
    private String projectMemberId;

    private String projectId;
    private String userId;
    private String projectRole;
    private String memberStatus;
    private String invitedBy;

    @Column(name = "joined_at")
    private OffsetDateTime joinedAt;

    @Column(name = "last_accessed_at")
    private OffsetDateTime lastAccessedAt;
}
