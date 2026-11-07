package org.myweb.flowmat.domain.user.repository;

import java.util.Optional;
import java.util.UUID;
import org.myweb.flowmat.domain.user.domain.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByRoleName(String roleName);
}
