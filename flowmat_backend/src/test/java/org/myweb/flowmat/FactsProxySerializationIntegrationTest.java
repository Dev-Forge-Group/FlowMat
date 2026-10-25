package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemRuleFactsQuery;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockRuleFactsQuery;
import org.myweb.flowmat.domain.inventory.domain.entity.Inventory;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowRunQuery;
import org.myweb.flowmat.domain.workflow.domain.entity.Process;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessIo;
import org.myweb.flowmat.domain.workflow.domain.entity.Workflow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rule facts (docs/architecture/adr/ADR-018-entity-boundary.md decision 5) must hold the same data when the caller already
 * holds an entity proxy; they were internal Facts JSON before.
 */
class FactsProxySerializationIntegrationTest extends IntegrationTestSupport {
    @Autowired private CatalogItemRuleFactsQuery itemRules;
    @Autowired private StockRuleFactsQuery stockRules;
    @Autowired private WorkflowRunQuery workflowRuns;
    @Autowired private EntityManager entities;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @ParameterizedTest
    @EnumSource(Kind.class)
    void anExistingUninitializedReferenceDoesNotBreakFactsSerialization(Kind kind) {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            entities.clear();
            Object reference = entities.getReference(kind.type, kind.id);
            assertFalse(Hibernate.isInitialized(reference), "The fixture must start as a real lazy proxy");
            String serialized = switch (kind) {
                case ITEM -> write(itemRules.findActiveItem(kind.id).orElseThrow());
                case WORKFLOW -> write(workflowRuns.findWorkflow(kind.id).orElseThrow().ruleFacts());
                case WORKFLOW_LOCK -> write(workflowRuns.lockWorkflow(kind.id).ruleFacts());
                case PROCESS -> write(workflowRuns.findProcess(kind.id).orElseThrow().ruleFacts());
                case PORT -> write(workflowRuns.findProcessIo(kind.id).orElseThrow().ruleFacts());
            };
            JsonNode tree = read(serialized);
            assertNotNull(tree.get(kind.idField));
            org.junit.jupiter.api.Assertions.assertEquals(kind.id, tree.path(kind.idField).asText());
            assertFalse(tree.has("hibernateLazyInitializer"), "An internal Facts payload must not contain ORM internals");
        });
    }

    @Test
    void stockRuleFactsKeepTheExistingProxySafeSerializationBehavior() {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into inventory(inventory_id,project_id,item_id,quantity,reserved_quantity,available_quantity,inventory_status) "
            + "values(?,?,?,10,0,10,'available')", id, DEMO_PROJECT, "itm_demo_mix_output");
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            entities.clear();
            Object reference = entities.getReference(Inventory.class, id);
            assertFalse(Hibernate.isInitialized(reference));
            JsonNode tree = read(write(stockRules.findActiveStock(id).orElseThrow()));
            org.junit.jupiter.api.Assertions.assertEquals(id, tree.path("inventoryId").asText());
            assertFalse(tree.has("hibernateLazyInitializer"));
        });
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            throw new AssertionError("Rule facts must serialize", error);
        }
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (Exception error) {
            throw new AssertionError("The Facts payload must be valid JSON", error);
        }
    }

    private enum Kind {
        ITEM(Item.class, "itm_demo_mix_output", "itemId"),
        WORKFLOW(Workflow.class, DEMO_WORKFLOW, "workflowId"),
        WORKFLOW_LOCK(Workflow.class, DEMO_WORKFLOW, "workflowId"),
        PROCESS(Process.class, "prc_demo_mix", "processId"),
        PORT(ProcessIo.class, "pio_demo_mix_in", "processIoId");

        private final Class<?> type;
        private final String id;
        private final String idField;

        Kind(Class<?> type, String id, String idField) {
            this.type = type;
            this.id = id;
            this.idField = idField;
        }
    }
}
