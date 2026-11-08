package org.myweb.flowmat.domain.catalog.application;

import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.api.dto.request.UnitCreateRequest;
import org.myweb.flowmat.domain.catalog.api.dto.request.UnitUpdateRequest;
import org.myweb.flowmat.domain.catalog.api.dto.response.UnitResponse;
import org.myweb.flowmat.domain.catalog.domain.entity.UnitMaster;
import org.myweb.flowmat.domain.catalog.repository.UnitMasterRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.myweb.flowmat.global.rbac.PermissionService;
import org.myweb.flowmat.global.rbac.SystemPermission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UnitServiceImpl implements UnitService {

    private static final String ACTIVE = "Y";
    private static final String INACTIVE = "N";

    private final UnitMasterRepository unitMasterRepository;
    private final PermissionService permissionService;
    private final IdGenerator idGenerator;

    @Override
    public List<UnitResponse> listUnits(boolean includeInactive) {
        List<UnitMaster> units = includeInactive
            ? unitMasterRepository.findAllByOrderByUnitTypeAscUnitCodeAsc()
            : unitMasterRepository.findAllByActiveYnOrderByUnitTypeAscUnitCodeAsc(ACTIVE);
        return units.stream().map(UnitServiceImpl::toResponse).toList();
    }

    @Override
    @Transactional
    public UnitResponse createUnit(UnitCreateRequest request) {
        permissionService.require(SystemPermission.MASTER_DATA_MANAGE);

        String unitCode = request.unitCode().trim();
        String unitType = request.unitType().trim().toLowerCase();
        if (unitMasterRepository.existsByUnitCodeIgnoreCase(unitCode)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Unit code '" + unitCode + "' already exists.");
        }

        UnitMaster unit = new UnitMaster();
        unit.setUnitId(idGenerator.generate());
        unit.setUnitCode(unitCode);
        unit.setUnitName(request.unitName().trim());
        unit.setUnitType(unitType);
        unit.setActiveYn(ACTIVE);

        String baseUnitCode = trimToNull(request.baseUnitCode());
        if (baseUnitCode == null) {
            unit.setBaseUnitCode(null);
            unit.setConversionRate(BigDecimal.ONE);
        } else {
            UnitMaster base = unitMasterRepository.findByUnitCodeIgnoreCase(baseUnitCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "Base unit '" + baseUnitCode + "' does not exist."));
            if (base.getBaseUnitCode() != null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "'" + base.getUnitCode() + "' is not a base unit.");
            }
            if (!unitType.equals(base.getUnitType())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Base unit '" + base.getUnitCode() + "' is a " + base.getUnitType() + " unit, not " + unitType + ".");
            }
            unit.setBaseUnitCode(base.getUnitCode());
            unit.setConversionRate(requirePositive(request.conversionRate()));
        }
        return toResponse(unitMasterRepository.save(unit));
    }

    @Override
    @Transactional
    public UnitResponse updateUnit(String unitId, UnitUpdateRequest request) {
        permissionService.require(SystemPermission.MASTER_DATA_MANAGE);
        UnitMaster unit = findUnit(unitId);

        if (request.unitName() != null && !request.unitName().isBlank()) {
            unit.setUnitName(request.unitName().trim());
        }
        if (request.conversionRate() != null) {
            if (unit.getBaseUnitCode() == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "A base unit always converts at rate 1.");
            }
            unit.setConversionRate(requirePositive(request.conversionRate()));
        }
        if (request.activeYn() != null) {
            if (INACTIVE.equalsIgnoreCase(request.activeYn())) {
                requireNoActiveDerivedUnits(unit);
                unit.setActiveYn(INACTIVE);
            } else {
                unit.setActiveYn(ACTIVE);
            }
        }
        return toResponse(unitMasterRepository.save(unit));
    }

    @Override
    @Transactional
    public void deactivateUnit(String unitId) {
        permissionService.require(SystemPermission.MASTER_DATA_MANAGE);
        UnitMaster unit = findUnit(unitId);
        requireNoActiveDerivedUnits(unit);
        // Units are never hard-deleted: items and recorded movements keep referring to them.
        unit.setActiveYn(INACTIVE);
        unitMasterRepository.save(unit);
    }

    private void requireNoActiveDerivedUnits(UnitMaster unit) {
        if (unit.getBaseUnitCode() == null
            && unitMasterRepository.existsByBaseUnitCodeIgnoreCaseAndActiveYn(unit.getUnitCode(), ACTIVE)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "Deactivate the units that convert to '" + unit.getUnitCode() + "' first.");
        }
    }

    private UnitMaster findUnit(String unitId) {
        return unitMasterRepository.findById(unitId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private static BigDecimal requirePositive(BigDecimal rate) {
        if (rate == null || rate.signum() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Conversion rate must be greater than 0.");
        }
        return rate;
    }

    private static String trimToNull(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private static UnitResponse toResponse(UnitMaster unit) {
        return new UnitResponse(
            unit.getUnitId(),
            unit.getUnitCode(),
            unit.getUnitName(),
            unit.getUnitType(),
            unit.getBaseUnitCode(),
            unit.getConversionRate(),
            unit.getActiveYn()
        );
    }
}
