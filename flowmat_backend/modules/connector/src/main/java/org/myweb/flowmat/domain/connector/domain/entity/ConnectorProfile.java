package org.myweb.flowmat.domain.connector.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "connector_profile")
public class ConnectorProfile {
    @Id private String profileId;
    private String projectId;
    private String name;
    private boolean enabled;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "deleted_yn", length = 1)
    private String deletedYn = "N";
    private int currentRevision;
    private long version;
    private String createdBy;
    private String updatedBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
