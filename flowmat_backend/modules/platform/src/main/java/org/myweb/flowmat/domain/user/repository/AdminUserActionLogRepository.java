package org.myweb.flowmat.domain.user.repository;

import java.util.List;
import java.util.UUID;
import org.myweb.flowmat.domain.user.domain.entity.AdminUserActionLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminUserActionLogRepository extends JpaRepository<AdminUserActionLog, UUID> {

    List<AdminUserActionLog> findByTargetUserIdOrderByCreatedAtDesc(String targetUserId);
}
