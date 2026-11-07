package org.myweb.flowmat.domain.production.api.dto.response;

/** A replay returns its original change and the order's current dates, which may have changed since then. */
public record WorkOrderRescheduleResult(WorkOrderResponse workOrder, WorkOrderRescheduleResponse change) {}
