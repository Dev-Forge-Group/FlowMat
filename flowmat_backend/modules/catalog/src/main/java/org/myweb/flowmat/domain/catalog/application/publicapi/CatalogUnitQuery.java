package org.myweb.flowmat.domain.catalog.application.publicapi;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
public interface CatalogUnitQuery {
    Optional<String> code(String unitId);
    Map<String,String> codes(Collection<String> ids);
}
