package org.myweb.flowmat.domain.connector.repository;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.connector.domain.entity.ConnectorProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConnectorProfileRepository extends JpaRepository<ConnectorProfile, String> {
    /** Reads mutable profile metadata and its immutable current configuration in one database snapshot. */
    @Query("select p.profileId as profileId, p.projectId as projectId, p.name as name, p.enabled as enabled, "
        + "p.version as version, p.currentRevision as currentRevision, v.profileVersionId as profileVersionId, "
        + "v.baseOrigin as baseOrigin, v.credentialAlias as credentialAlias, v.operationsJson as operationsJson, "
        + "p.createdAt as createdAt, p.updatedAt as updatedAt "
        + "from ConnectorProfile p left join ConnectorProfileVersion v "
        + "on v.profileId = p.profileId and v.revision = p.currentRevision "
        + "where p.projectId = :projectId and p.deletedYn = :deletedYn "
        + "order by p.createdAt desc, p.profileId asc")
    List<CurrentProfile> findAllByProjectIdAndDeletedYnOrderByCreatedAtDescProfileIdAsc(
        @Param("projectId") String projectId, @Param("deletedYn") String deletedYn);

    interface CurrentProfile {
        String getProfileId();
        String getProjectId();
        String getName();
        boolean isEnabled();
        long getVersion();
        int getCurrentRevision();
        String getProfileVersionId();
        String getBaseOrigin();
        String getCredentialAlias();
        String getOperationsJson();
        OffsetDateTime getCreatedAt();
        OffsetDateTime getUpdatedAt();
    }
    Optional<ConnectorProfile> findByProfileIdAndProjectIdAndDeletedYn(String profileId, String projectId, String deletedYn);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ConnectorProfile p where p.profileId = :profileId and p.projectId = :projectId and p.deletedYn = 'N'")
    Optional<ConnectorProfile> lockActive(@Param("projectId") String projectId, @Param("profileId") String profileId);
}
