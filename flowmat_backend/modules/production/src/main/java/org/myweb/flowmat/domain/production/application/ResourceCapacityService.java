package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.request.ResourceCapacityRequest;
import org.myweb.flowmat.domain.production.api.dto.response.ResourceCapacityResponse;
import org.myweb.flowmat.domain.production.domain.entity.ResourceCapacity;
import org.myweb.flowmat.domain.production.repository.ResourceCapacityRepository;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A project's resource limits (docs/architecture/adr/ADR-046-resources-first-class.md decision 5): anyone who reads the
 * project sees them, its owner replaces the whole list. Plans compare their average draw with them.
 */
@Service
@RequiredArgsConstructor
public class ResourceCapacityService {

    private static final Set<String> TYPES = Set.of("energy", "water");
    private static final Set<String> PERIODS = Set.of("hour", "day");
    private static final BigDecimal MAX = new BigDecimal("10000000000");

    private final ResourceCapacityRepository capacities;
    private final ProjectAccess projectAccess;

    @Transactional(readOnly = true)
    public List<ResourceCapacityResponse> list(String projectId) {
        projectAccess.requireProjectReadAccess(projectId);
        return capacities.findAllByProjectIdOrderByResourceTypeAscUnitAsc(projectId).stream()
            .map(ResourceCapacityService::response).toList();
    }

    @Transactional
    public List<ResourceCapacityResponse> replace(String projectId, ResourceCapacityRequest request) {
        projectAccess.requireProjectOwnerAccess(projectId);
        Set<String> seen = new HashSet<>();
        List<ResourceCapacity> rows = request.capacities().stream().map(given -> {
            String type = text(given.resourceType()).toLowerCase(Locale.ROOT);
            String unit = text(given.unit());
            String per = text(given.per()).toLowerCase(Locale.ROOT);
            if (!TYPES.contains(type)) throw bad("resourceType must be energy or water.");
            if (unit.isEmpty() || unit.length() > 30) throw bad("unit must be 1 to 30 characters.");
            if (!PERIODS.contains(per)) throw bad("per must be hour or day.");
            if (given.amount() == null || given.amount().signum() <= 0 || given.amount().compareTo(MAX) >= 0
                || given.amount().stripTrailingZeros().scale() > 4) {
                throw bad("amount must be more than 0, below 10000000000, with at most 4 decimals.");
            }
            if (!seen.add(type + " " + unit.toLowerCase(Locale.ROOT))) throw bad("Each resource and unit can have one limit.");
            ResourceCapacity row = new ResourceCapacity();
            row.setProjectId(projectId);
            row.setResourceType(type);
            row.setUnit(unit);
            row.setAmount(given.amount().setScale(4, RoundingMode.UNNECESSARY));
            row.setPer(per);
            row.setUpdatedBy(projectAccess.requireCurrentUserId());
            row.setUpdatedAt(OffsetDateTime.now());
            return row;
        }).toList();
        capacities.deleteAllOfProject(projectId);
        capacities.saveAllAndFlush(rows);
        return capacities.findAllByProjectIdOrderByResourceTypeAscUnitAsc(projectId).stream()
            .map(ResourceCapacityService::response).toList();
    }

    private static ResourceCapacityResponse response(ResourceCapacity row) {
        return new ResourceCapacityResponse(row.getResourceType(), row.getUnit(), row.getAmount(), row.getPer(), row.getUpdatedBy(),
            row.getUpdatedAt());
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
