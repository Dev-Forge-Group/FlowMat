package org.myweb.flowmat.domain.notification.repository;

import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.notification.domain.entity.NotificationSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationSubscriptionRepository extends JpaRepository<NotificationSubscription, String> {

    List<NotificationSubscription> findAllByProjectIdAndUserId(String projectId, String userId);

    Optional<NotificationSubscription> findByProjectIdAndUserIdAndTopic(String projectId, String userId, String topic);

    List<NotificationSubscription> findAllByProjectIdAndTopic(String projectId, String topic);

    List<NotificationSubscription> findAllByTopic(String topic);
}
