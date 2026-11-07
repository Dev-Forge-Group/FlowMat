package org.myweb.flowmat.domain.production.application;

import java.util.List;
import org.myweb.flowmat.domain.production.api.dto.request.WorkOrderCreateRequest;
import org.myweb.flowmat.domain.production.api.dto.request.WorkOrderUpdateRequest;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderResponse;

public interface WorkOrderService {

    List<WorkOrderResponse> listWorkOrders(String projectId);

    WorkOrderResponse getWorkOrder(String workOrderId);

    WorkOrderResponse createWorkOrder(WorkOrderCreateRequest request);

    WorkOrderResponse updateWorkOrder(String workOrderId, WorkOrderUpdateRequest request);

    WorkOrderResponse approveWorkOrder(String workOrderId);

    WorkOrderResponse cancelWorkOrder(String workOrderId);

    WorkOrderResponse completeWorkOrder(String workOrderId);
}
