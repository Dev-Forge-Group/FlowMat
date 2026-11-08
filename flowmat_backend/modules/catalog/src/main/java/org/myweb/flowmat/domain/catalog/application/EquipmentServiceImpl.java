package org.myweb.flowmat.domain.catalog.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.api.dto.request.EquipmentCreateRequest;
import org.myweb.flowmat.domain.catalog.api.dto.request.EquipmentDetails;
import org.myweb.flowmat.domain.catalog.api.dto.request.EquipmentUpdateRequest;
import org.myweb.flowmat.domain.catalog.api.dto.response.EquipmentResponse;
import org.myweb.flowmat.domain.catalog.api.dto.response.EquipmentStatusChangeResponse;
import org.myweb.flowmat.domain.catalog.domain.entity.Equipment;
import org.myweb.flowmat.domain.catalog.domain.entity.EquipmentStatusHistory;
import org.myweb.flowmat.domain.catalog.repository.EquipmentRepository;
import org.myweb.flowmat.domain.catalog.repository.EquipmentStatusHistoryRepository;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EquipmentServiceImpl implements EquipmentService {
    private static final String NOT_DELETED = "N";
    private static final Set<String> STATUSES = Set.of("active", "inactive", "maintenance");
    private final EquipmentRepository equipmentRepository;
    private final ProjectAccess projectAccessService;
    private final IdGenerator idGenerator;
    private final EquipmentStatusHistoryRepository statusHistoryRepository;

    @Override
    public List<EquipmentResponse> listEquipment(String projectId) {
        String id = required(projectId, "projectId");
        projectAccessService.requireProjectReadAccess(id);
        return equipmentRepository.findAllByProjectIdAndDeletedYnOrderByCreatedAtDesc(id, NOT_DELETED)
            .stream().map(EquipmentServiceImpl::toResponse).toList();
    }

    @Override
    public EquipmentResponse getEquipment(String equipmentId) {
        Equipment equipment = findActive(equipmentId);
        projectAccessService.requireProjectReadAccess(equipment.getProjectId());
        return toResponse(equipment);
    }

    @Override
    @Transactional
    public EquipmentResponse createEquipment(EquipmentCreateRequest request) {
        String projectId = required(request.projectId(), "projectId");
        projectAccessService.requireProjectWriteAccess(projectId);
        String code = optional(request.equipmentCode());
        if (code != null && equipmentRepository.existsByProjectIdAndEquipmentCodeIgnoreCaseAndDeletedYn(projectId, code, NOT_DELETED)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Equipment code already exists in this project.");
        }
        Equipment equipment = new Equipment();
        equipment.setEquipmentId(idGenerator.generate());
        equipment.setProjectId(projectId);
        equipment.setEquipmentCode(code);
        equipment.setEquipmentName(required(request.equipmentName(), "equipmentName"));
        equipment.setEquipmentType(required(request.equipmentType(), "equipmentType"));
        equipment.setEquipmentStatus("active");
        if (request.details() != null) applyDetails(equipment, request.details());
        equipment.setCreatedBy(projectAccessService.requireCurrentUserId());
        Equipment saved = equipmentRepository.save(equipment);
        recordStatus(saved, null, null);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public EquipmentResponse updateEquipment(String equipmentId, EquipmentUpdateRequest request) {
        Equipment equipment = findActive(equipmentId);
        projectAccessService.requireProjectWriteAccess(equipment.getProjectId());
        if (request.equipmentName() != null) equipment.setEquipmentName(required(request.equipmentName(), "equipmentName"));
        if (request.equipmentType() != null) equipment.setEquipmentType(required(request.equipmentType(), "equipmentType"));
        String statusBefore = equipment.getEquipmentStatus();
        if (request.equipmentStatus() != null) {
            String status = required(request.equipmentStatus(), "equipmentStatus").toLowerCase(java.util.Locale.ROOT);
            if (!STATUSES.contains(status)) throw new BusinessException(ErrorCode.BAD_REQUEST, "Invalid equipmentStatus.");
            equipment.setEquipmentStatus(status);
        }
        if (request.details() != null) applyDetails(equipment, request.details());
        equipment.setUpdatedBy(projectAccessService.requireCurrentUserId());
        Equipment saved = equipmentRepository.save(equipment);
        if (!Objects.equals(statusBefore, saved.getEquipmentStatus())) {
            recordStatus(saved, statusBefore, request.statusNote());
        }
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void deleteEquipment(String equipmentId) {
        Equipment equipment = findActive(equipmentId);
        projectAccessService.requireProjectOwnerAccess(equipment.getProjectId());
        equipment.setDeletedYn("Y");
        equipment.setUpdatedBy(projectAccessService.requireCurrentUserId());
        equipmentRepository.save(equipment);
    }

    @Override
    public List<EquipmentStatusChangeResponse> statusHistory(String equipmentId) {
        Equipment equipment = findActive(equipmentId);
        projectAccessService.requireProjectReadAccess(equipment.getProjectId());
        return statusHistoryRepository.findAllByEquipmentIdOrderByChangedAtDesc(equipment.getEquipmentId()).stream()
            .map(change -> new EquipmentStatusChangeResponse(change.getPreviousStatus(), change.getEquipmentStatus(), change.getNote(),
                change.getChangedBy(), change.getChangedAt()))
            .toList();
    }

    /** Keeps the equipment's new status with the one before it (none when it was just added) and why. */
    private void recordStatus(Equipment equipment, String before, String note) {
        EquipmentStatusHistory change = new EquipmentStatusHistory();
        change.setEquipmentStatusHistoryId(idGenerator.generate());
        change.setEquipmentId(equipment.getEquipmentId());
        change.setPreviousStatus(before);
        change.setEquipmentStatus(equipment.getEquipmentStatus());
        change.setNote(note == null || note.isBlank() ? null : note.trim());
        change.setChangedBy(projectAccessService.requireCurrentUserId());
        change.setChangedAt(OffsetDateTime.now());
        statusHistoryRepository.save(change);
    }

    private Equipment findActive(String id) {
        return equipmentRepository.findByEquipmentIdAndDeletedYn(id, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new BusinessException(ErrorCode.BAD_REQUEST, name + " is required.");
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Replaces every detail; blank text and null numbers clear. */
    private static void applyDetails(Equipment equipment, EquipmentDetails details) {
        equipment.setManufacturer(optional(details.manufacturer()));
        equipment.setModelName(optional(details.modelName()));
        equipment.setSerialNo(optional(details.serialNo()));
        equipment.setCapacityPerHour(nonNegative(details.capacityPerHour(), "capacityPerHour"));
        equipment.setPowerKwh(nonNegative(details.powerKwh(), "powerKwh"));
        equipment.setWaterLiter(nonNegative(details.waterLiter(), "waterLiter"));
        equipment.setLocation(optional(details.location()));
        equipment.setEfficiencyPercent(efficiency(details.efficiencyPercent()));
        equipment.setConcurrentWorkOrdersYn(Boolean.TRUE.equals(details.concurrentWorkOrders()) ? "Y" : "N");
        equipment.setConcurrentWorkOrdersLimit(concurrentLimit(details));
    }

    /** Above 0 to 100 with up to two decimals; null keeps the rated output (E8). */
    private static BigDecimal efficiency(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "efficiencyPercent must be above 0 and at most 100.");
        }
        if (value.stripTrailingZeros().scale() > 2) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "efficiencyPercent takes at most 2 decimals.");
        }
        return value;
    }

    /** With side-by-side work orders, at most 2 to 99 at once; none is no limit (E16). */
    private static Integer concurrentLimit(EquipmentDetails details) {
        Integer limit = details.concurrentLimit();
        if (limit == null) {
            return null;
        }
        if (!Boolean.TRUE.equals(details.concurrentWorkOrders())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "concurrentLimit needs concurrentWorkOrders.");
        }
        if (limit < 2 || limit > 99) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, EquipmentDetails.WholeLimit.MESSAGE);
        }
        return limit;
    }

    private static BigDecimal nonNegative(BigDecimal value, String name) {
        if (value != null && value.signum() < 0) throw new BusinessException(ErrorCode.BAD_REQUEST, name + " must not be negative.");
        return value;
    }

    private static EquipmentResponse toResponse(Equipment equipment) {
        return new EquipmentResponse(equipment.getEquipmentId(), equipment.getProjectId(), equipment.getEquipmentCode(),
            equipment.getEquipmentName(), equipment.getEquipmentType(), equipment.getEquipmentStatus(),
            new EquipmentDetails(equipment.getManufacturer(), equipment.getModelName(), equipment.getSerialNo(),
                equipment.getCapacityPerHour(), equipment.getPowerKwh(), equipment.getWaterLiter(), equipment.getLocation(),
                equipment.getEfficiencyPercent(), "Y".equals(equipment.getConcurrentWorkOrdersYn()),
                equipment.getConcurrentWorkOrdersLimit()));
    }
}
