package org.myweb.flowmat.domain.connector.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.connector.domain.entity.ConnectorProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConnectorProfileRepository extends JpaRepository<ConnectorProfile, String> {
    List<ConnectorProfile> findAllByProjectIdAndDeletedYnOrderByCreatedAtDescProfileIdAsc(String projectId, String deletedYn);
    Optional<ConnectorProfile> findByProfileIdAndProjectIdAndDeletedYn(String profileId, String projectId, String deletedYn);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ConnectorProfile p where p.profileId = :profileId and p.projectId = :projectId and p.deletedYn = 'N'")
    Optional<ConnectorProfile> lockActive(@Param("projectId") String projectId, @Param("profileId") String profileId);
}
