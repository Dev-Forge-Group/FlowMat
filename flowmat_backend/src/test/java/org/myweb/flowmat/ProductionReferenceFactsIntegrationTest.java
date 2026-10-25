package org.myweb.flowmat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowRunQuery;
import org.myweb.flowmat.domain.workflow.domain.entity.Process;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessIo;
import org.myweb.flowmat.domain.workflow.domain.entity.Workflow;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemRuleFactsQuery;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockRuleFactsQuery;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockViewQuery;
import org.myweb.flowmat.domain.inventory.domain.entity.Inventory;
import org.myweb.flowmat.domain.inventory.repository.InventoryRepository;
import org.myweb.flowmat.domain.workflow.repository.*;
import org.myweb.flowmat.domain.catalog.repository.ItemRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

class ProductionReferenceFactsIntegrationTest extends IntegrationTestSupport {
    @Autowired WorkflowRunQuery workflowRuns;
    @Autowired CatalogItemRuleFactsQuery itemRules;
    @Autowired StockRuleFactsQuery stockRules;
    @Autowired StockViewQuery stockViews;
    @Autowired InventoryRepository inventoryRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkflowRepository workflowRepository;
    @Autowired ProcessRepository processRepository;
    @Autowired ProcessIoRepository ioRepository;
    @Autowired ItemRepository itemRepository;
    @Autowired ObjectMapper json;
    @Test void legacyRuleFactsKeepAllFieldsAndAuditValuesAcrossPublicBoundaries() throws Exception {
        assertEquals(asRulesSawIt(workflowRepository.findById(DEMO_WORKFLOW).orElseThrow(), Workflow.class),
            workflowRuns.findWorkflow(DEMO_WORKFLOW).orElseThrow().ruleFacts());
        assertEquals(asRulesSawIt(processRepository.findById("prc_demo_mix").orElseThrow(), Process.class),
            workflowRuns.findProcess("prc_demo_mix").orElseThrow().ruleFacts());
        assertEquals(asRulesSawIt(ioRepository.findById("pio_demo_mix_in").orElseThrow(), ProcessIo.class),
            workflowRuns.findProcessIo("pio_demo_mix_in").orElseThrow().ruleFacts());
        // Rule facts are what rules were given before ADR-018 decision 5: the entity as JSON, read back, as the evaluator's map.
        assertEquals(asRulesSawIt(itemRepository.findById("itm_demo_mix_output").orElseThrow(), Item.class),
            itemRules.findActiveItem("itm_demo_mix_output").orElseThrow());
        String stock = UUID.randomUUID().toString();
        jdbc.update("insert into inventory(inventory_id,project_id,item_id,quantity,reserved_quantity,available_quantity,inventory_status,location) "
            + "values(?,?,?,12.5,2,10.5,'available','RULES')", stock, DEMO_PROJECT, "itm_demo_mix_output");
        assertEquals(asRulesSawIt(inventoryRepository.findById(stock).orElseThrow(), Inventory.class),
            stockRules.findActiveStock(stock).orElseThrow());
        assertTrue(stockRules.findActiveStock("missing").isEmpty());
        assertTrue(stockViews.findActiveStock("missing").isEmpty());
        assertTrue(workflowRuns.findWorkflow("missing").isEmpty());
        assertTrue(workflowRuns.findProcess("missing").isEmpty());
        assertTrue(workflowRuns.findProcessIo("missing").isEmpty());
        assertTrue(itemRules.findActiveItem("missing").isEmpty());
    }

    private <T> Map<String, Object> asRulesSawIt(T row, Class<T> type) throws Exception {
        return json.convertValue(json.readValue(json.writeValueAsString(row), type), new TypeReference<Map<String, Object>>() { });
    }
}
