package org.myweb.flowmat.domain.user.repository;

import java.util.List;
import java.util.UUID;
import org.myweb.flowmat.domain.user.domain.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRoleRepository extends JpaRepository<UserRole, UUID> {

    List<UserRole> findByUserIdAndScopeType(UUID userId, String scopeType);
}
