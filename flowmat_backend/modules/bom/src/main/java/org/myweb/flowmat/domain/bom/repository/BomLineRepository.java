package org.myweb.flowmat.domain.bom.repository;

import java.util.Collection;
import java.util.List;
import org.myweb.flowmat.domain.bom.domain.entity.BomLine;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BomLineRepository extends JpaRepository<BomLine, String> {

    List<BomLine> findAllByBomIdOrderBySortOrderAscBomLineIdAsc(String bomId);

    List<BomLine> findAllByBomIdIn(Collection<String> bomIds);

    /** Lines that use an item, across BOMs; for where-used. */
    List<BomLine> findAllByChildItemId(String childItemId);
}
