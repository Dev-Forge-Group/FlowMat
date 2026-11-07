package org.myweb.flowmat.domain.production.application.publicapi;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Work order reads for other bounded contexts, which use this instead of the production repositories
 * (docs/architecture/adr/ADR-002-module-dependency.md). Add an operation only when a caller needs it.
 */
public interface WorkOrderQuery {

    /** The work order if it exists, is not deleted and belongs to the project. */
    Optional<WorkOrderView> findProjectWorkOrder(String projectId, String workOrderId);

    /** Work orders by id, deleted ones included so old references still show a number; ids that do not exist are left out. */
    Map<String, WorkOrderView> findWorkOrders(Collection<String> workOrderIds);

    /**
     * Approved or running work orders of the projects whose planned end is at or before {@code until}, deleted ones left out,
     * for reminders (docs/domain/notification.md NS6).
     */
    List<WorkOrderDueView> findDueBy(Collection<String> projectIds, OffsetDateTime until);
}
