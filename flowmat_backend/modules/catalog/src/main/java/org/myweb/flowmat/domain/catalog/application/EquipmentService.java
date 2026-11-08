package org.myweb.flowmat.domain.catalog.application;

import java.util.List;
import org.myweb.flowmat.domain.catalog.api.dto.request.EquipmentCreateRequest;
import org.myweb.flowmat.domain.catalog.api.dto.request.EquipmentUpdateRequest;
import org.myweb.flowmat.domain.catalog.api.dto.response.EquipmentResponse;
import org.myweb.flowmat.domain.catalog.api.dto.response.EquipmentStatusChangeResponse;

public interface EquipmentService {
    List<EquipmentResponse> listEquipment(String projectId);
    EquipmentResponse getEquipment(String equipmentId);
    EquipmentResponse createEquipment(EquipmentCreateRequest request);
    EquipmentResponse updateEquipment(String equipmentId, EquipmentUpdateRequest request);
    void deleteEquipment(String equipmentId);
    /** Its status changes, newest first (docs/domain/equipment.md "상태 이력"). */
    List<EquipmentStatusChangeResponse> statusHistory(String equipmentId);
}
