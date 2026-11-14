package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A BOM explosion and the material requirements read level by level, not line by line
 * (docs/operations/performance-baseline.md), against real Postgres: a tree three times as wide, or five times the orders,
 * sends exactly as many SQL statements. Before the reads were batched, each line cost an item and two unit reads.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class BomReadBatchingIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @Test
    void anExplosionSendsTheSameStatementsForAWideTreeAsForANarrowOne() throws Exception {
        Tree narrow = tree(2);
        Tree wide = tree(6);
        MockHttpServletRequestBuilder narrowCall = get("/boms/" + narrow.productBom() + "/explosion").param("quantity", "10");
        MockHttpServletRequestBuilder wideCall = get("/boms/" + wide.productBom() + "/explosion").param("quantity", "10");

        // Two assemblies of two materials each, against six of six: 6 lines and 42.
        assertThat(ok(narrowCall, null).path("lines").size()).isEqualTo(2 + 2 * 2);
        JsonNode exploded = ok(wideCall, null);
        assertThat(exploded.path("lines").size()).isEqualTo(6 + 6 * 6);
        assertThat(exploded.path("materials").size()).isEqualTo(36);
        // 10 products take 20 of each assembly and 40 of each material.
        assertThat(exploded.path("materials").get(0).path("quantity").decimalValue()).isEqualByComparingTo("40");

        long few = statements(narrowCall);
        long many = statements(wideCall);
        assertThat(many).as("Seven times the lines must not send more statements (%d for 6 lines)", few).isEqualTo(few);
    }

    @Test
    void materialRequirementsSendTheSameStatementsForTenOrdersAsForTwo() throws Exception {
        Tree small = tree(3);
        Tree large = tree(3);
        orders(small, 2);
        orders(large, 10);
        MockHttpServletRequestBuilder smallCall = get("/material-requirements").param("projectId", small.project());
        MockHttpServletRequestBuilder largeCall = get("/material-requirements").param("projectId", large.project());

        // Nothing is in stock, so each product ordered needs 2 of each assembly and 4 of each material.
        JsonNode two = ok(smallCall, null);
        JsonNode ten = ok(largeCall, null);
        assertThat(two.path("orders").asInt()).isEqualTo(2);
        assertThat(ten.path("orders").asInt()).isEqualTo(10);
        assertThat(ten.path("lines").size()).isEqualTo(3 + 9);
        assertThat(required(two, small.materials().get(0))).isEqualByComparingTo("12");
        assertThat(required(ten, large.materials().get(0))).isEqualByComparingTo("220");

        long few = statements(smallCall);
        long many = statements(largeCall);
        assertThat(many).as("Five times the orders must not send more statements (%d for two)", few).isEqualTo(few);
    }

    /** A product of {@code fan} assemblies, each of {@code fan} materials, 2 of each child, in its own project. */
    private Tree tree(int fan) throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String project = ok(post("/projects"), Map.of("projectName", "Read batching " + tag, "ownerId", DEMO_OWNER))
            .path("projectId").asText();
        String product = item(project, "RB-P-" + tag, "product");
        List<String> assemblies = new ArrayList<>();
        List<String> materials = new ArrayList<>();
        for (int a = 0; a < fan; a++) {
            String assembly = item(project, "RB-A" + a + "-" + tag, "semi_product");
            List<String> own = new ArrayList<>();
            for (int m = 0; m < fan; m++) {
                own.add(item(project, "RB-M" + a + "-" + m + "-" + tag, "material"));
            }
            bom(project, assembly, own);
            assemblies.add(assembly);
            materials.addAll(own);
        }
        return new Tree(project, product, bom(project, product, assemblies), materials);
    }

    /** Approved orders of the product for 1, 2, ... of it. */
    private void orders(Tree tree, int count) throws Exception {
        for (int i = 1; i <= count; i++) {
            String order = ok(post("/work-orders"), Map.of("projectId", tree.project(), "workOrderTitle", "Read batching " + i,
                "targetItemId", tree.product(), "targetQuantity", i, "bomId", tree.productBom())).path("workOrderId").asText();
            ok(post("/work-orders/" + order + "/approve"), null);
        }
    }

    private String item(String project, String code, String type) throws Exception {
        return ok(post("/items"), Map.of("projectId", project, "itemCode", code, "itemName", code.toLowerCase(), "itemType", type,
            "unitId", "unit_ea")).path("itemId").asText();
    }

    private String bom(String project, String parent, List<String> children) throws Exception {
        String bom = ok(post("/boms"), Map.of("projectId", project, "targetItemId", parent, "bomName", "Read batching",
            "baseQuantity", 1, "baseUnit", "ea")).path("bomId").asText();
        for (String child : children) {
            ok(post("/boms/" + bom + "/lines"), Map.of("childItemId", child, "quantity", 2, "unit", "ea"));
        }
        ok(post("/boms/" + bom + "/submit"), null);
        ok(post("/boms/" + bom + "/approve"), null);
        return bom;
    }

    private static java.math.BigDecimal required(JsonNode summary, String itemId) {
        for (JsonNode line : summary.path("lines")) {
            if (itemId.equals(line.path("itemId").asText())) {
                return line.path("required").decimalValue();
            }
        }
        throw new AssertionError("No line for " + itemId + " in " + summary);
    }

    /**
     * SQL statements one call sends, the fewest of three runs: the session factory's count is the whole application's, so
     * a background job's statement during one run must not be taken for the call's.
     */
    private long statements(MockHttpServletRequestBuilder call) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean enabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            long fewest = Long.MAX_VALUE;
            for (int run = 0; run < 3; run++) {
                long before = statistics.getPrepareStatementCount();
                ok(call, null);
                fewest = Math.min(fewest, statistics.getPrepareStatementCount() - before);
            }
            return fewest;
        } finally {
            statistics.setStatisticsEnabled(enabled);
        }
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        JsonNode envelope = mapper.readTree(response.getContentAsByteArray());
        assertThat(response.getStatus()).as(envelope.path("message").asText()).isEqualTo(200);
        return envelope.path("data");
    }

    private record Tree(String project, String product, String productBom, List<String> materials) {}
}
