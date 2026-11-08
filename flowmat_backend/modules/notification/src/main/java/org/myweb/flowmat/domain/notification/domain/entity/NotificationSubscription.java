package org.myweb.flowmat.domain.notification.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** One topic a person chose to hear about in one project (docs/domain/notification.md NS1, V79). Deleted to stop. */
@Getter
@Setter
@Entity
@Table(name = "notification_subscription")
public class NotificationSubscription {

    @Id
    private String subscriptionId;

    private String projectId;
    private String userId;
    /** stock_alert, ncr_check_overdue or work_order_due. */
    private String topic;
    /** work_order_due: how many days before the planned end to tell (0-30); null for the other topics. */
    private Integer daysAhead;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
