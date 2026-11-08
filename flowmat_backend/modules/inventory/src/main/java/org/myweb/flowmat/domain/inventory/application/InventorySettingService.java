package org.myweb.flowmat.domain.inventory.application;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventorySettingRequest;
import org.myweb.flowmat.domain.inventory.api.dto.response.InventorySettingResponse;
import org.myweb.flowmat.domain.inventory.domain.entity.InventorySetting;
import org.myweb.flowmat.domain.inventory.repository.InventorySettingRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A project's inventory settings (docs/domain/storage-location.md SL6): members read them, only the owner changes them, from
 * the version the form was opened at.
 */
@Service
@RequiredArgsConstructor
public class InventorySettingService {
    static final String WARN = "warn";
    static final String REFUSE = "refuse";
    private static final List<String> POLICIES = List.of(WARN, REFUSE);

    private final InventorySettingRepository settings;
    private final ProjectAccessService access;

    @Transactional(readOnly = true)
    public InventorySettingResponse get(String projectId) {
        String project = required(projectId);
        access.requireProjectReadAccess(project);
        return response(project, settings.findById(project));
    }

    /** The same policy again answers as it is, so a repeat after a lost answer is harmless. */
    @Transactional
    public InventorySettingResponse set(InventorySettingRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Send the inventory settings.");
        }
        String projectId = required(request.projectId());
        access.requireProjectOwnerAccess(projectId);
        String policy = request.storageConditionPolicy() == null ? null : request.storageConditionPolicy().trim();
        if (policy == null || !POLICIES.contains(policy)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "storageConditionPolicy is warn or refuse.");
        }
        settings.lockKey("inventory-setting|" + projectId);
        Optional<InventorySetting> current = settings.findById(projectId);
        if (current.isPresent() && policy.equals(current.get().getStorageConditionPolicy())) {
            return response(projectId, current);
        }
        if (!Objects.equals(current.map(InventorySetting::getVersion).orElse(null), request.expectedVersion())) {
            throw new BusinessException(ErrorCode.CONFLICT, "The inventory settings changed since they were opened; reload them and try again.");
        }
        InventorySetting saved = current.orElseGet(() -> {
            InventorySetting created = new InventorySetting();
            created.setProjectId(projectId);
            created.setVersion(-1);
            return created;
        });
        saved.setStorageConditionPolicy(policy);
        saved.setVersion(saved.getVersion() + 1);
        saved.setUpdatedBy(access.requireCurrentUserId());
        saved.setUpdatedAt(OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS));
        return response(projectId, Optional.of(settings.saveAndFlush(saved)));
    }

    private static InventorySettingResponse response(String projectId, Optional<InventorySetting> found) {
        return found.map(one -> new InventorySettingResponse(projectId, one.getStorageConditionPolicy(), one.getVersion(), one.getUpdatedBy(),
                one.getUpdatedAt()))
            .orElseGet(() -> new InventorySettingResponse(projectId, WARN, null, null, null));
    }

    private static String required(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "projectId is required.");
        }
        return projectId.trim();
    }
}
