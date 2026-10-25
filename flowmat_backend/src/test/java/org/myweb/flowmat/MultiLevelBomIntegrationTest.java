package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Multi-level BOMs (docs/domain/multi-level-bom.md): explosion and multi-level material needs against real Postgres. */
@AutoConfigureMockMvc
class MultiLevelBomIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    /** A cake is two sponges, 0.5 kg cream and 0.1 kg sugar; a sponge is 0.2 kg flour and 2 eggs; 1 kg cream is 0.8 kg milk and 0.2 kg sugar. */
    private record Cake(String cake, String sponge, String cream, String flour, String egg, String milk, String sugar,
                        String cakeBom, String spongeBom, String tag) {
    }

    @Test
    void anApprovedBomExplodesThroughItsSubAssemblies() throws Exception {
        Cake c = cake();
        String rows = "$.data.lines[?(@.itemId == '%s')]";
        call(get("/boms/" + c.cakeBom() + "/explosion").param("quantity", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.levels").value(2))
            .andExpect(jsonPath("$.data.lines.length()").value(7))
            .andExpect(jsonPath(rows.formatted(c.sponge()) + ".level").value(hasItem(1)))
            .andExpect(jsonPath(rows.formatted(c.sponge()) + ".quantity").value(hasItem(20.0)))
            .andExpect(jsonPath(rows.formatted(c.sponge()) + ".bomId").value(hasItem(c.spongeBom())))
            .andExpect(jsonPath(rows.formatted(c.flour()) + ".level").value(hasItem(2)))
            .andExpect(jsonPath(rows.formatted(c.flour()) + ".parentItemId").value(hasItem(c.sponge())))
            .andExpect(jsonPath(rows.formatted(c.flour()) + ".quantity").value(hasItem(4.0)))
            .andExpect(jsonPath(rows.formatted(c.egg()) + ".quantity").value(hasItem(40.0)))
            .andExpect(jsonPath(rows.formatted(c.milk()) + ".quantity").value(hasItem(4.0)))
            // Sugar goes into the cake and into the cream: 1 kg each.
            .andExpect(jsonPath("$.data.materials.length()").value(4))
            .andExpect(jsonPath("$.data.materials[?(@.itemId == '" + c.sugar() + "')].quantity").value(hasItem(2.0)))
            .andExpect(jsonPath("$.data.materials[?(@.itemId == '" + c.sponge() + "')]").isEmpty())
            // flour 4 × 2 + eggs 40 × 0.5 + milk 4 × 1 + sugar 2 × 3
            .andExpect(jsonPath("$.data.materialCost").value(38.0))
            .andExpect(jsonPath("$.data.costComplete").value(true));

        call(get("/boms/" + c.cakeBom() + "/explosion").param("quantity", "0")).andExpect(status().isBadRequest());
        call(get("/boms/" + c.cakeBom() + "/explosion").param("quantity", "lots")).andExpect(status().isBadRequest());
        call(get("/boms/" + c.cakeBom() + "/explosion")).andExpect(status().isBadRequest());
        String draft = id(call(post("/boms/" + c.cakeBom() + "/revisions")), "bomId");
        call(get("/boms/" + draft + "/explosion").param("quantity", "1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("approved")));
        callAs("unrelated-user", get("/boms/" + c.cakeBom() + "/explosion").param("quantity", "1")).andExpect(status().isForbidden());
    }

    @Test
    void openOrderNeedsGoThroughShortSubAssemblies() throws Exception {
        Cake c = cake();
        // Five sponges are in stock, so 15 of the 20 a 10-cake order needs are made; no cream is, so all 5 kg are made.
        call(post("/inventories"), json(Map.of("projectId", DEMO_PROJECT, "itemId", c.sponge(), "quantity", 5, "location", "ML-" + c.tag())))
            .andExpect(status().isOk());
        order("Cakes " + c.tag(), c.cake(), 10, c.cakeBom());

        String line = "$.data.lines[?(@.itemId == '%s')]";
        needs()
            .andExpect(jsonPath(line.formatted(c.sponge()) + ".required").value(hasItem(20.0)))
            .andExpect(jsonPath(line.formatted(c.sponge()) + ".usable").value(hasItem(5.0)))
            .andExpect(jsonPath(line.formatted(c.sponge()) + ".shortage").value(hasItem(15.0)))
            .andExpect(jsonPath(line.formatted(c.sponge()) + ".madeHere").value(hasItem(true)))
            .andExpect(jsonPath(line.formatted(c.flour()) + ".required").value(hasItem(3.0)))
            .andExpect(jsonPath(line.formatted(c.flour()) + ".madeHere").value(hasItem(false)))
            .andExpect(jsonPath(line.formatted(c.flour()) + ".orders[0].viaItemId").value(hasItem(c.sponge())))
            .andExpect(jsonPath(line.formatted(c.egg()) + ".required").value(hasItem(30.0)))
            .andExpect(jsonPath(line.formatted(c.milk()) + ".required").value(hasItem(4.0)))
            .andExpect(jsonPath(line.formatted(c.sugar()) + ".required").value(hasItem(2.0)))
            .andExpect(jsonPath(line.formatted(c.sugar()) + ".orders.length()").value(hasItem(2)));

        // An approved order for 15 sponges now covers them: the cake order no longer makes sponges itself, and the
        // flour and eggs are the sponge order's own needs.
        order("Sponges " + c.tag(), c.sponge(), 15, c.spongeBom());
        needs()
            .andExpect(jsonPath(line.formatted(c.sponge()) + ".plannedSupply").value(hasItem(15.0)))
            // Order by order, so the screen can tell when it comes (docs/domain/purchasing.md PO25).
            .andExpect(jsonPath(line.formatted(c.sponge()) + ".supplies[0].workOrderTitle").value(hasItem("Sponges " + c.tag())))
            .andExpect(jsonPath(line.formatted(c.sponge()) + ".supplies[0].quantity").value(hasItem(15.0)))
            .andExpect(jsonPath(line.formatted(c.sponge()) + ".shortage").value(hasItem(0.0)))
            .andExpect(jsonPath(line.formatted(c.flour()) + ".required").value(hasItem(3.0)))
            .andExpect(jsonPath(line.formatted(c.flour()) + ".orders[0].workOrderTitle").value(hasItem("Sponges " + c.tag())))
            .andExpect(jsonPath(line.formatted(c.egg()) + ".required").value(hasItem(30.0)));
    }

    @Test
    void whereUsedClimbsThroughSubAssembliesToTheTopProducts() throws Exception {
        Cake c = cake();
        // A draft that uses sugar is not part of the approved tree.
        bom(item("ML-DRAFT-" + c.tag(), "unit_ea", null), 1, "ea", c.sugar(), 1, "kg", false);

        // Sugar: straight into the cake (0.1 kg) and into the cream (0.2 kg per kg), which the cake takes 0.5 kg of.
        String uses = "$.data.uses";
        call(get("/boms/where-used/all-levels").param("projectId", DEMO_PROJECT).param("itemId", c.sugar()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.unit").value("kg"))
            .andExpect(jsonPath(uses + ".length()").value(3))
            .andExpect(jsonPath(uses + "[0].level").value(1))
            .andExpect(jsonPath(uses + "[0].productItemId").value(c.cake()))
            .andExpect(jsonPath(uses + "[0].perProductUnit").value(0.1))
            .andExpect(jsonPath(uses + "[0].topLevel").value(true))
            .andExpect(jsonPath(uses + "[1].productItemId").value(c.cream()))
            .andExpect(jsonPath(uses + "[1].perProductUnit").value(0.2))
            .andExpect(jsonPath(uses + "[1].topLevel").value(false))
            .andExpect(jsonPath(uses + "[2].level").value(2))
            .andExpect(jsonPath(uses + "[2].materialItemId").value(c.cream()))
            .andExpect(jsonPath(uses + "[2].productItemId").value(c.cake()))
            .andExpect(jsonPath(uses + "[2].perProductUnit").value(0.1))
            .andExpect(jsonPath(uses + "[2].path.length()").value(3))
            .andExpect(jsonPath("$.data.topProducts.length()").value(1))
            .andExpect(jsonPath("$.data.topProducts[0].itemId").value(c.cake()))
            .andExpect(jsonPath("$.data.topProducts[0].perUnit").value(0.2))
            .andExpect(jsonPath("$.data.topProducts[0].routes").value(2))
            .andExpect(jsonPath("$.data.topProducts[0].levels").value(2))
            .andExpect(jsonPath("$.data.problems.length()").value(0));

        // Flour: 0.2 kg per sponge, two sponges per cake.
        call(get("/boms/where-used/all-levels").param("projectId", DEMO_PROJECT).param("itemId", c.flour()))
            .andExpect(jsonPath("$.data.uses[0].productItemId").value(c.sponge()))
            .andExpect(jsonPath("$.data.uses[1].perProductUnit").value(0.4))
            .andExpect(jsonPath("$.data.topProducts[0].perUnit").value(0.4))
            .andExpect(jsonPath("$.data.topProducts[0].routes").value(1));

        // The cake itself is used by nothing.
        call(get("/boms/where-used/all-levels").param("projectId", DEMO_PROJECT).param("itemId", c.cake()))
            .andExpect(jsonPath("$.data.uses.length()").value(0))
            .andExpect(jsonPath("$.data.topProducts.length()").value(0));
        call(get("/boms/where-used/all-levels").param("projectId", DEMO_PROJECT).param("itemId", "no-such-item"))
            .andExpect(status().isBadRequest());
        callAs("ml-outsider", get("/boms/where-used/all-levels").param("projectId", DEMO_PROJECT).param("itemId", c.sugar()))
            .andExpect(status().isForbidden());
    }

    @Test
    void costRollsUpFromBoughtMaterialsThroughSubAssemblies() throws Exception {
        Cake c = cake();
        // A custard made of milk and vanilla, which has no unit cost yet.
        String vanilla = item("ML-VAN-" + c.tag(), "unit_kg", null);
        String custard = item("ML-CUSTARD-" + c.tag(), "unit_kg", null);
        String custardBom = bom(custard, 2, "kg", c.milk(), 2, "kg", true);
        // Drafts do not count: a new custard revision and a vanilla BOM still in draft leave the roll-up alone.
        call(post("/boms/" + custardBom + "/revisions")).andExpect(status().isOk());
        call(post("/boms"), json(Map.of("projectId", DEMO_PROJECT, "targetItemId", vanilla, "bomName", "draft", "baseQuantity", 1,
            "baseUnit", "kg"))).andExpect(status().isOk());
        call(put("/items/" + c.cake()), json(Map.of("unitCost", 5))).andExpect(status().isOk());

        String row = "$.data.items[?(@.itemId == '%s')]";
        call(get("/boms/cost-rollup").param("projectId", DEMO_PROJECT))
            .andExpect(status().isOk())
            // sponge: 0.2 kg flour × 2 + 2 eggs × 0.5; cream per kg: 0.8 kg milk × 1 + 0.2 kg sugar × 3
            .andExpect(jsonPath(row.formatted(c.sponge()) + ".rolledUpCost").value(hasItem(1.4)))
            .andExpect(jsonPath(row.formatted(c.sponge()) + ".levels").value(hasItem(1)))
            .andExpect(jsonPath(row.formatted(c.cream()) + ".rolledUpCost").value(hasItem(1.4)))
            // cake: 2 sponges × 1.4 + 0.5 kg cream × 1.4 + 0.1 kg sugar × 3, the explosion's 38 for ten
            .andExpect(jsonPath(row.formatted(c.cake()) + ".rolledUpCost").value(hasItem(3.8)))
            .andExpect(jsonPath(row.formatted(c.cake()) + ".levels").value(hasItem(2)))
            .andExpect(jsonPath(row.formatted(c.cake()) + ".complete").value(hasItem(true)))
            .andExpect(jsonPath(row.formatted(c.cake()) + ".currentUnitCost").value(hasItem(5.0)))
            .andExpect(jsonPath(row.formatted(c.cake()) + ".bomId").value(hasItem(c.cakeBom())))
            // custard per kg: 1 kg milk × 1, all known
            .andExpect(jsonPath(row.formatted(custard) + ".rolledUpCost").value(hasItem(1.0)))
            .andExpect(jsonPath(row.formatted(custard) + ".complete").value(hasItem(true)));

        // A material with no cost: what is known is still added up, and the gap is named.
        String flan = item("ML-FLAN-" + c.tag(), "unit_ea", null);
        String flanBom = bom(flan, 1, "ea", custard, 0.25, "kg", false);
        line(flanBom, vanilla, 10, "g");
        approve(flanBom);
        call(get("/boms/cost-rollup").param("projectId", DEMO_PROJECT))
            .andExpect(jsonPath(row.formatted(flan) + ".rolledUpCost").value(hasItem(0.25)))
            .andExpect(jsonPath(row.formatted(flan) + ".complete").value(hasItem(false)))
            .andExpect(jsonPath(row.formatted(flan) + ".missingCosts[0]").value(hasItem("ML-VAN-" + c.tag())));
        callAs("ml-outsider", get("/boms/cost-rollup").param("projectId", DEMO_PROJECT)).andExpect(status().isForbidden());
    }

    private Cake cake() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String cake = item("ML-CAKE-" + tag, "unit_ea", null);
        String sponge = item("ML-SPONGE-" + tag, "unit_ea", null);
        String cream = item("ML-CREAM-" + tag, "unit_kg", null);
        String flour = item("ML-FLOUR-" + tag, "unit_kg", 2);
        String egg = item("ML-EGG-" + tag, "unit_ea", 0.5);
        String milk = item("ML-MILK-" + tag, "unit_kg", 1);
        String sugar = item("ML-SUGAR-" + tag, "unit_kg", 3);
        String spongeBom = bom(sponge, 1, "ea", flour, 0.2, "kg", false);
        line(spongeBom, egg, 2, "ea");
        approve(spongeBom);
        String creamBom = bom(cream, 1, "kg", milk, 0.8, "kg", false);
        line(creamBom, sugar, 0.2, "kg");
        approve(creamBom);
        String cakeBom = bom(cake, 1, "ea", sponge, 2, "ea", false);
        line(cakeBom, cream, 0.5, "kg");
        line(cakeBom, sugar, 0.1, "kg");
        approve(cakeBom);
        return new Cake(cake, sponge, cream, flour, egg, milk, sugar, cakeBom, spongeBom, tag);
    }

    private String bom(String target, Number base, String baseUnit, String child, Number quantity, String unit, boolean approve)
        throws Exception {
        String bomId = id(call(post("/boms"), json(Map.of("projectId", DEMO_PROJECT, "targetItemId", target, "bomName", "ML " + target,
            "baseQuantity", base, "baseUnit", baseUnit))), "bomId");
        line(bomId, child, quantity, unit);
        if (approve) {
            approve(bomId);
        }
        return bomId;
    }

    private void line(String bomId, String child, Number quantity, String unit) throws Exception {
        call(post("/boms/" + bomId + "/lines"), json(Map.of("childItemId", child, "quantity", quantity, "unit", unit)))
            .andExpect(status().isOk());
    }

    private void approve(String bomId) throws Exception {
        call(post("/boms/" + bomId + "/submit")).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/approve")).andExpect(status().isOk());
    }

    private void order(String title, String itemId, int quantity, String bomId) throws Exception {
        String orderId = id(call(post("/work-orders"), json(Map.of("projectId", DEMO_PROJECT, "workOrderTitle", title,
            "workflowId", DEMO_WORKFLOW, "targetItemId", itemId, "targetQuantity", quantity, "bomId", bomId))), "workOrderId");
        call(post("/work-orders/" + orderId + "/approve")).andExpect(status().isOk());
    }

    private ResultActions needs() throws Exception {
        return call(get("/material-requirements").param("projectId", DEMO_PROJECT)).andExpect(status().isOk());
    }

    private String item(String code, String unitId, Number unitCost) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", DEMO_PROJECT);
        body.put("itemCode", code);
        body.put("itemName", code.toLowerCase());
        body.put("itemType", "material");
        body.put("unitId", unitId);
        body.put("unitCost", unitCost);
        return id(call(post("/items"), json(body)), "itemId");
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private String id(ResultActions result, String field) throws Exception {
        return data(result).path(field).asText();
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return callAs(DEMO_OWNER, request);
    }

    private ResultActions callAs(String userId, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(userId)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
