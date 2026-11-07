package org.myweb.flowmat.domain.connector.repository;

import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.connector.domain.entity.ConnectorProfileVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConnectorProfileVersionRepository extends JpaRepository<ConnectorProfileVersion, String> {
    Optional<ConnectorProfileVersion> findByProfileIdAndRevision(String profileId, int revision);
    List<ConnectorProfileVersion> findAllByProfileIdOrderByRevisionDesc(String profileId);

    @Query("select v from ConnectorProfileVersion v, ConnectorProfile p where v.profileId = p.profileId "
        + "and v.revision = p.currentRevision and p.projectId = :projectId and p.deletedYn = 'N'")
    List<ConnectorProfileVersion> findCurrentVersions(@Param("projectId") String projectId);
}
