package org.myweb.flowmat.domain.notification.repository;

import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.notification.domain.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, String> {

    List<Notification> findTop100ByUserIdOrderByCreatedAtDescNfcIdDesc(String userId);

    List<Notification> findAllByUserIdAndRead(String userId, boolean read);

    Optional<Notification> findByNfcIdAndUserId(String nfcId, String userId);

    boolean existsByUserIdAndDedupeKey(String userId, String dedupeKey);
}
