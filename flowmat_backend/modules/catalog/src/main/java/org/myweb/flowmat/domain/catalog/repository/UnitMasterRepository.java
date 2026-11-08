package org.myweb.flowmat.domain.catalog.repository;

import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.catalog.domain.entity.UnitMaster;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UnitMasterRepository extends JpaRepository<UnitMaster, String> {

    List<UnitMaster> findAllByOrderByUnitTypeAscUnitCodeAsc();

    List<UnitMaster> findAllByActiveYnOrderByUnitTypeAscUnitCodeAsc(String activeYn);

    Optional<UnitMaster> findByUnitCodeIgnoreCase(String unitCode);

    boolean existsByUnitCodeIgnoreCase(String unitCode);

    boolean existsByBaseUnitCodeIgnoreCaseAndActiveYn(String baseUnitCode, String activeYn);
}
