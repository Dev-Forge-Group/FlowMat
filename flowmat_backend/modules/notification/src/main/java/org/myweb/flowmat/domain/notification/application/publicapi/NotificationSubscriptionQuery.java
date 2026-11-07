package org.myweb.flowmat.domain.notification.application.publicapi;

import java.util.List;

/**
 * Who chose which topic in which project (docs/domain/notification.md NS1), for the reminder sweep, which uses this instead
 * of the notification repositories (docs/architecture/adr/ADR-002-module-dependency.md).
 */
public interface NotificationSubscriptionQuery {

    String STOCK_ALERT = "stock_alert";
    String NCR_CHECK_OVERDUE = "ncr_check_overdue";
    String WORK_ORDER_DUE = "work_order_due";
    List<String> TOPICS = List.of(STOCK_ALERT, NCR_CHECK_OVERDUE, WORK_ORDER_DUE);

    /** Every subscription to {@code topic}, in all projects. */
    List<NotificationSubscriptionView> subscriptions(String topic);
}
