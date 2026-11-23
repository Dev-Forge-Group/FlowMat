package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogUnitCostQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogUnitCostView.CostBasis;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** D+ catalog prices using real, isolated PostgreSQL history and no BOM creation. */
@AutoConfigureMockMvc
class CatalogUnitCostQueryIntegrationTest extends IntegrationTestSupport {
    private static final OffsetDateTime FIRST = OffsetDateTime.parse("2030-01-01T00:00:00Z");
    @Autowired private CatalogUnitCostQuery query;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;

    @Test
    void historyQuerySelectsAnInclusiveInstantAndKeepsDeletedItemReferences() throws Exception {
        String item = item(10);
        cost(item, 20);
        cost(item, 30);
        at(item, 10, FIRST);
        at(item, 20, FIRST.plusDays(1));
        at(item, 30, FIRST.plusDays(2));
        jdbc.update("UPDATE item SET deleted_yn = 'Y' WHERE item_id = ?", item);

        var prices = query.findUnitCostsAt(DEMO_PROJECT, Set.of(item, "no-such-item"), FIRST.plusDays(1));
        assertThat(prices).containsOnlyKeys(item);
        assertThat(prices.get(item).unitCost()).isEqualByComparingTo("20");
        assertThat(prices.get(item).costBasis()).isEqualTo(CostBasis.HISTORICAL);
        assertThat(query.findUnitCostsAt("another-project", Set.of(item), FIRST)).isEmpty();
    }

    @Test
    void itemsWithoutHistoryAreMarkedEstimatedWhileCurrentPricesAreNot() throws Exception {
        String first = item(10);
        String second = item(20);
        // Model items from before V50; only this test's newly created history rows are removed.
        jdbc.update("DELETE FROM item_cost_history WHERE item_id IN (?, ?)", first, second);
        var prices = query.findUnitCostsAt(DEMO_PROJECT, Set.of(first, second), FIRST);
        assertThat(prices).containsOnlyKeys(first, second);
        assertThat(prices.get(first).unitCost()).isEqualByComparingTo("10");
        assertThat(prices.get(second).unitCost()).isEqualByComparingTo("20");
        assertThat(prices.values()).allMatch(row -> row.costBasis() == CostBasis.ESTIMATED);
        assertThat(query.findUnitCostsAt(DEMO_PROJECT, Set.of(first), null).get(first).costBasis()).isEqualTo(CostBasis.CURRENT);
    }

    @Test
    void aRecordedUnknownHistoricalPriceDoesNotUseTheNewKnownPrice() throws Exception {
        String item = item(10);
        cost(item, 0);
        cost(item, 30);
        at(item, 10, FIRST);
        at(item, 0, FIRST.plusDays(1));
        at(item, 30, FIRST.plusDays(2));
        var price = query.findUnitCostsAt(DEMO_PROJECT, Set.of(item), FIRST.plusDays(1)).get(item);
        assertThat(price.unitCost()).isNull();
        assertThat(price.costBasis()).isEqualTo(CostBasis.HISTORICAL);
    }

    private String item(int price) throws Exception {
        var result = mvc.perform(post("/items").header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER))
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("projectId", DEMO_PROJECT,
                "itemCode", "DPLUS-" + UUID.randomUUID().toString().substring(0, 8), "itemName", "historical price",
                "unitId", "unit_kg", "unitCost", price))))
            .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("data").path("itemId").asText();
    }

    private void cost(String item, int price) throws Exception {
        mvc.perform(put("/items/" + item).header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER))
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("unitCost", price))))
            .andExpect(status().isOk());
    }

    private void at(String item, int price, OffsetDateTime at) {
        assertThat(jdbc.update("UPDATE item_cost_history SET changed_at = ? WHERE item_id = ? AND unit_cost = ?",
            at, item, BigDecimal.valueOf(price))).isEqualTo(1);
    }
}
