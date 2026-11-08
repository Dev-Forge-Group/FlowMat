package org.myweb.flowmat.domain.connector.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Immutable
@Entity
@Table(name = "connector_profile_version")
public class ConnectorProfileVersion {
    @Id private String profileVersionId;
    private String profileId;
    private int revision;
    private String baseOrigin;
    private String credentialAlias;
    @JdbcTypeCode(SqlTypes.JSON)
    private String operationsJson;
    private String createdBy;
    private OffsetDateTime createdAt;
}
