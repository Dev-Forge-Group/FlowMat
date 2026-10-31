package org.myweb.flowmat.domain.inventory.application;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemRuleFactsQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogUnitQuery;
import org.springframework.stereotype.Component;
/** Local catalog reads through its public view, never its Item entity (docs/architecture/adr/ADR-018-entity-boundary.md). */
@Component
@RequiredArgsConstructor
public class InventoryCatalogReferences {
    private final CatalogQuery catalog;
    private final CatalogUnitQuery units;
    private final CatalogItemRuleFactsQuery ruleFacts;
    public Optional<CatalogItemView> item(String id) { return catalog.findActiveItem(id); }
    public Optional<CatalogItemView> lockItemForStock(String projectId, String itemId) {
        return catalog.lockProjectItemForStock(projectId, itemId);
    }
    public List<CatalogItemView> items(Collection<String> ids) { return List.copyOf(catalog.findItems(ids).values()); }
    public List<CatalogItemView> projectItems(String id) { return catalog.findProjectItems(id); }
    public Optional<Map<String,Object>> itemRuleFacts(String id) { return ruleFacts.findActiveItem(id); }
    public Optional<String> unitCode(String id) { return units.code(id); }
    public Map<String,String> unitCodes(Collection<String> ids) { return units.codes(ids); }
}
