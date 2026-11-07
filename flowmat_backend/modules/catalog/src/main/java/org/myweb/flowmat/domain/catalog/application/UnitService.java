package org.myweb.flowmat.domain.catalog.application;

import java.util.List;
import org.myweb.flowmat.domain.catalog.api.dto.request.UnitCreateRequest;
import org.myweb.flowmat.domain.catalog.api.dto.request.UnitUpdateRequest;
import org.myweb.flowmat.domain.catalog.api.dto.response.UnitResponse;

public interface UnitService {

    List<UnitResponse> listUnits(boolean includeInactive);

    UnitResponse createUnit(UnitCreateRequest request);

    UnitResponse updateUnit(String unitId, UnitUpdateRequest request);

    void deactivateUnit(String unitId);
}
