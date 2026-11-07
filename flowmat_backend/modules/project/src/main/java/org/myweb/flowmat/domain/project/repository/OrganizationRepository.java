package org.myweb.flowmat.domain.project.repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.project.domain.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrganizationRepository extends JpaRepository<Organization, String> {
    Optional<Organization> findByOrganizationIdAndDeletedYn(String organizationId, String deletedYn);

    Optional<Organization> findByOwnerUserIdAndOrganizationTypeAndDeletedYn(String ownerUserId, String organizationType, String deletedYn);

    List<Organization> findAllByOrganizationIdInAndDeletedYnOrderByOrganizationNameAsc(Collection<String> organizationIds, String deletedYn);

    /** Serializes membership changes of one organization, so it never loses its last owner. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Organization o where o.organizationId = :id and o.deletedYn = 'N'")
    Optional<Organization> findForUpdate(@Param("id") String organizationId);
}
