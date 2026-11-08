package org.myweb.flowmat.domain.catalog.application;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogUnitQuery;
import org.myweb.flowmat.domain.catalog.repository.UnitMasterRepository;
import org.myweb.flowmat.domain.catalog.domain.entity.UnitMaster;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
@RequiredArgsConstructor
@Transactional(readOnly=true)
public class CatalogUnitQueryImpl implements CatalogUnitQuery {
    private final UnitMasterRepository units;
    public Optional<String> code(String id) { return units.findById(id).map(UnitMaster::getUnitCode); }
    public Map<String,String> codes(Collection<String> ids) { return units.findAllById(ids).stream().collect(Collectors.toUnmodifiableMap(UnitMaster::getUnitId,UnitMaster::getUnitCode)); }
}
