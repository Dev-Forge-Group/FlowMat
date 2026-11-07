package org.myweb.flowmat.domain.user.repository;

import java.util.UUID;
import org.myweb.flowmat.domain.user.domain.entity.UserLoginHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserLoginHistoryRepository extends JpaRepository<UserLoginHistory, UUID> {
}
