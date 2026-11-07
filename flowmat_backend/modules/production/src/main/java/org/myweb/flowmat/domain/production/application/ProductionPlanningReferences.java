package org.myweb.flowmat.domain.production.application;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogUnitQuery;
import org.myweb.flowmat.domain.inventory.application.publicapi.LotQuery;
import org.myweb.flowmat.domain.inventory.application.publicapi.LotView;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockView;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockViewQuery;
import org.myweb.flowmat.domain.bom.application.publicapi.BomRevisionQuery;
import org.myweb.flowmat.domain.bom.application.publicapi.BomRevisionView;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowRunQuery;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowRunView;
import org.springframework.stereotype.Component;
/**
 * Local adapters. Items, BOM revisions, stock records, LOTs and workflows come as their owners' public views
 * (docs/architecture/adr/ADR-018-entity-boundary.md). Repositories and locks stay in their owning domains.
 */
@Component
@RequiredArgsConstructor
public class ProductionPlanningReferences {
    private final CatalogQuery catalog;
    private final CatalogUnitQuery units;
    private final StockViewQuery stocks;
    private final LotQuery lots;
    private final BomRevisionQuery revisions;
    private final WorkflowRunQuery workflows;
    public Optional<CatalogItemView> item(String id) { return catalog.findActiveItem(id); }
    public List<CatalogItemView> items(Collection<String> ids) { return List.copyOf(catalog.findItems(ids).values()); }
    public Optional<String> unitCode(String id) { return units.code(id); }
    public List<StockView> stocks(Collection<String> ids) { return stocks.findStocks(ids); }
    public List<StockView> projectStocks(String id) { return stocks.projectStocks(id); }
    public List<StockView> lockItemStock(String project,String item) { return stocks.lockItemStock(project,item); }
    public List<LotView> lots(Collection<String> ids) { return List.copyOf(lots.findLots(ids).values()); }
    public Optional<BomRevisionView> bom(String id) { return revisions.findActive(id); }
    public Optional<WorkflowRunView> workflow(String id) { return workflows.findWorkflow(id); }
}
