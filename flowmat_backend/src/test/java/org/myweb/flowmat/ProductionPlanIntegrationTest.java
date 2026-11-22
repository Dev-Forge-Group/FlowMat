package org.myweb.flowmat;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/**
 * A plan from a target against real Postgres (docs/architecture/adr/ADR-043-plan-backwards-from-a-target.md, the
 * manufacturing layer): the common layer's needs by item against usable stock, what stock could make now, and the refusals.
 */
@AutoConfigureMockMvc
class ProductionPlanIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    /**
     * Melon milk, 1,000 bottles: Pack takes 24 filled bottles and a box for 24; 1% breaks before Pack and 2% of the mix is
     * lost before Fill (0.2 kg a bottle); a mix of 100 kg milk, 10 kg syrup and 5 kg sugar makes 112 kg. Raw milk is short.
     */
    @Test
    void aTargetNeedsItsMaterialsAgainstStock() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String milk = item("PL-MILK-" + tag, "unit_kg");
        String syrup = item("PL-SYRUP-" + tag, "unit_kg");
        String sugar = item("PL-SUGAR-" + tag, "unit_kg");
        String bottle = item("PL-BOTTLE-" + tag, "unit_ea");
        String box = item("PL-BOX-" + tag, "unit_ea");
        String mixed = item("PL-MIX-" + tag, "unit_kg");
        String filled = item("PL-FILLED-" + tag, "unit_ea");
        String melonMilk = item("PL-MELON-" + tag, "unit_ea");
        stock(milk, 150, tag);
        stock(syrup, 20, tag);
        stock(sugar, 50, tag);
        stock(bottle, 2000, tag);
        stock(box, 50, tag);

        Graph graph = new Graph("Melon milk " + tag);
        String receive = graph.node("Receive milk");
        String mix = graph.node("Mix");
        String fill = graph.node("Fill");
        String pack = graph.node("Pack");
        String receiveIn = graph.port(receive, "input", milk, 1, "kg");
        String receiveOut = graph.port(receive, "output", milk, 1, "kg");
        String mixMilk = graph.port(mix, "input", milk, 100, "kg");
        graph.port(mix, "input", syrup, 10, "kg");
        graph.port(mix, "input", sugar, 5, "kg");
        String mixOut = graph.port(mix, "output", mixed, 112, "kg");
        String fillMix = graph.port(fill, "input", mixed, 0.2, "kg");
        graph.port(fill, "input", bottle, 1, "ea");
        String fillOut = graph.port(fill, "output", filled, 1, "ea");
        String packFilled = graph.port(pack, "input", filled, 24, "ea");
        graph.port(pack, "input", box, 1, "ea");
        String packOut = graph.port(pack, "output", melonMilk, 24, "ea");
        graph.connect(receive, receiveOut, mix, mixMilk, null);
        graph.connect(mix, mixOut, fill, fillMix, 0.02);
        graph.connect(fill, fillOut, pack, packFilled, 0.01);
        // A mix takes the mixer 30 minutes, a bottle the filler 3 seconds, a box two people a minute (ADR-043 decision 4).
        String mixer = equipment("PL-MIXER-" + tag);
        String filler = equipment("PL-FILLER-" + tag);
        requirements(mix, Map.of("kind", "equipment", "ref", mixer, "amount", 1), Map.of("kind", "time", "amount", 30, "unit", "min"));
        requirements(fill, Map.of("kind", "equipment", "ref", filler, "amount", 1),
            Map.of("kind", "time", "amount", 3, "unit", "s", "per", "output_unit"));
        requirements(pack, Map.of("kind", "crew", "amount", 2), Map.of("kind", "time", "amount", 1));
        String revision = graph.publish();

        String row = "$.data.requirements[?(@.itemId == '%s')]";
        call(post("/workflows/" + graph.workflow + "/plan-previews"),
            json(Map.of("target", Map.of("processIoId", packOut, "quantity", 1000, "unit", "ea"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.revisionId").value(revision))
            .andExpect(jsonPath("$.data.feasible").value(true))
            // 150 kg of milk makes 814.968 bottles; bottles are counted, so 814.
            .andExpect(jsonPath("$.data.buildable").value(814))
            .andExpect(jsonPath(row.formatted(milk) + ".needed").value(hasItem(184.0564)))
            .andExpect(jsonPath(row.formatted(milk) + ".available").value(hasItem(150.0)))
            .andExpect(jsonPath(row.formatted(milk) + ".shortage").value(hasItem(34.0564)))
            .andExpect(jsonPath(row.formatted(milk) + ".unit").value(hasItem("kg")))
            .andExpect(jsonPath(row.formatted(syrup) + ".needed").value(hasItem(18.4057)))
            .andExpect(jsonPath(row.formatted(syrup) + ".shortage").value(hasItem(0.0)))
            .andExpect(jsonPath(row.formatted(sugar) + ".needed").value(hasItem(9.2029)))
            .andExpect(jsonPath(row.formatted(bottle) + ".needed").value(hasItem(1010.1011)))
            .andExpect(jsonPath(row.formatted(box) + ".needed").value(hasItem(41.6667)))
            .andExpect(jsonPath("$.data.requirements.length()").value(5))
            // The milk's reason runs back through every node.
            .andExpect(jsonPath(row.formatted(milk) + ".trace[*].processId").value(hasItems(pack, fill, mix, receive)))
            .andExpect(jsonPath("$.data.limits[0].kind").value("stock"))
            .andExpect(jsonPath("$.data.limits[0].at").value(receiveIn))
            // Then the longest equipment step: Mix, 1.8406 batches of 30 minutes, longer than Fill's 1,010.1011 × 3 s.
            .andExpect(jsonPath("$.data.limits[1].kind").value("equipment-time"))
            .andExpect(jsonPath("$.data.limits[1].at").value(mix))
            .andExpect(jsonPath("$.data.limits.length()").value(2))
            .andExpect(jsonPath("$.data.nodes[?(@.processId == '" + mix + "')].batches").value(hasItem(1.8406)))
            .andExpect(jsonPath("$.data.nodes[?(@.processId == '" + mix + "')].minutes").value(hasItem(55.2169)))
            .andExpect(jsonPath("$.data.nodes[?(@.processId == '" + fill + "')].minutes").value(hasItem(50.5051)))
            .andExpect(jsonPath("$.data.nodes[?(@.processId == '" + pack + "')].minutes").value(hasItem(41.6667)))
            .andExpect(jsonPath("$.data.basis.available['" + milk + "']").value(150.0));
    }

    /** A plan needs read access, a published revision and a graph it can plan. */
    @Test
    void refusesWhatCannotBePlanned() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String product = item("PL-ONE-" + tag, "unit_ea");
        Graph graph = new Graph("Unpublished " + tag);
        String make = graph.node("Make");
        String out = graph.port(make, "output", product, 1, "ea");
        String body = json(Map.of("target", Map.of("processIoId", out, "quantity", 10)));

        call(post("/workflows/" + graph.workflow + "/plan-previews"), body).andExpect(status().isConflict());
        graph.publish();
        mockMvc.perform(post("/workflows/" + graph.workflow + "/plan-previews").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("plan-outsider")))
            .andExpect(status().isForbidden());
        call(post("/workflows/" + graph.workflow + "/plan-previews"),
            json(Map.of("target", Map.of("processIoId", out, "quantity", 0))))
            .andExpect(status().isConflict());
        call(post("/workflows/" + graph.workflow + "/plan-previews"), body).andExpect(status().isOk());
        call(put("/processes/" + make + "/requirements"), json(Map.of("requirements", java.util.List.of(Map.of("kind", "magic", "amount", 1)))))
            .andExpect(status().isBadRequest());
    }

    /**
     * Confirming a plan (ADR-043 decision 7): a draft work order and a draft purchase order for the shortage, once per requestId,
     * stock unmoved; a newer revision or other usable stock refuses it and makes nothing; outsiders cannot.
     */
    @Test
    void aConfirmedPlanMakesDraftsOnceAndMovesNoStock() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        // Its own project: listing a supplier makes a project take only listed ones (PO20), which other tests would feel.
        String project = id(call(post("/projects"), json(Map.of("projectName", "Plan confirm " + tag, "ownerId", DEMO_OWNER))), "projectId");
        String flour = item(project, "PC-FLOUR-" + tag, "unit_kg");
        String bread = item(project, "PC-BREAD-" + tag, "unit_ea");
        stock(project, flour, 10, tag);
        call(post("/suppliers"), json(Map.of("projectId", project, "supplierName", "Mill " + tag))).andExpect(status().isOk());
        Graph graph = new Graph(project, "Bread " + tag);
        String bake = graph.node("Bake");
        graph.port(bake, "input", flour, 2, "kg");
        String loaves = graph.port(bake, "output", bread, 1, "ea");
        graph.publish();
        Map<String, Object> target = Map.of("processIoId", loaves, "quantity", 10, "unit", "ea");
        var plan = objectMapper.readTree(call(post("/workflows/" + graph.workflow + "/plan-previews"), json(Map.of("target", target)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        Map<String, Object> basis = objectMapper.convertValue(plan.path("basis"), Map.class);
        String url = "/workflows/" + graph.workflow + "/plan-confirmations";
        Map<String, Object> confirm = Map.of("target", target, "basis", basis, "requestId", "pc-" + tag, "supplierName", "Mill " + tag);

        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json(confirm))
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("plan-outsider")))
            .andExpect(status().isForbidden());
        var made = objectMapper.readTree(call(post(url), json(confirm)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("data");
        String workOrder = made.path("workOrderId").asText();
        String order = made.path("purchaseOrderIds").path(0).asText();
        call(post(url), json(confirm)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.workOrderId").value(workOrder))
            .andExpect(jsonPath("$.data.purchaseOrderIds[0]").value(order));
        call(get("/work-orders/" + workOrder)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.workOrderStatus").value("draft"))
            .andExpect(jsonPath("$.data.targetItemId").value(bread))
            .andExpect(jsonPath("$.data.targetQuantity").value(10.0));
        call(get("/purchase-orders/" + order)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("draft"))
            .andExpect(jsonPath("$.data.lines[0].itemId").value(flour))
            .andExpect(jsonPath("$.data.lines[0].quantity").value(10.0));
        call(get("/inventories").param("projectId", project))
            .andExpect(jsonPath("$.data[?(@.itemId == '" + flour + "')].quantity").value(hasItem(10.0)));

        // A newer revision: the old plan is refused and nothing more is made.
        graph.publish();
        Map<String, Object> stale = new HashMap<>(confirm);
        stale.put("requestId", "pc-stale-" + tag);
        call(post(url), json(stale)).andExpect(status().isConflict());
        // Planned again but stock moved meanwhile: refused too.
        var fresh = objectMapper.readTree(call(post("/workflows/" + graph.workflow + "/plan-previews"), json(Map.of("target", target)))
            .andReturn().getResponse().getContentAsString()).path("data");
        stock(project, flour, 1, tag + "B");
        Map<String, Object> moved = new HashMap<>(stale);
        moved.put("basis", objectMapper.convertValue(fresh.path("basis"), Map.class));
        call(post(url), json(moved)).andExpect(status().isConflict());
        call(get("/purchase-orders").param("projectId", project))
            .andExpect(jsonPath("$.data[?(@.supplierName == 'Mill " + tag + "')]").value(org.hamcrest.Matchers.hasSize(1)));
    }

    private final class Graph {
        private final String workflow;

        Graph(String name) throws Exception {
            this(DEMO_PROJECT, name);
        }

        Graph(String project, String name) throws Exception {
            workflow = id(call(post("/workflows"), json(Map.of("projectId", project, "workflowName", name))), "workflowId");
        }

        String node(String name) throws Exception {
            return id(call(post("/processes"), json(Map.of("workflowId", workflow, "processName", name, "nodeType", "process"))),
                "processId");
        }

        String port(String process, String direction, String itemId, double quantity, String unit) throws Exception {
            // Materials drawn from stock have no connection in, so they are not required inputs (REQUIRED_INPUT_UNCONNECTED).
            return id(call(post("/process-ios"), json(Map.of("processId", process, "direction", direction, "itemId", itemId,
                "quantity", quantity, "unit", unit, "resourceType", "material", "requiredYn", "N"))), "processIoId");
        }

        void connect(String from, String fromIo, String to, String toIo, Double lossRate) throws Exception {
            Map<String, Object> body = new HashMap<>(Map.of("workflowId", workflow, "fromProcessId", from, "toProcessId", to,
                "fromIoId", fromIo, "toIoId", toIo));
            if (lossRate != null) body.put("lossRate", lossRate);
            call(post("/process-connections"), json(body)).andExpect(status().isOk());
        }

        String publish() throws Exception {
            return id(call(post("/workflows/" + workflow + "/revisions")), "workflowRevisionId");
        }
    }

    @SafeVarargs
    private void requirements(String process, Map<String, Object>... requirements) throws Exception {
        call(put("/processes/" + process + "/requirements"), json(Map.of("requirements", java.util.List.of(requirements))))
            .andExpect(status().isOk());
    }

    private String equipment(String code) throws Exception {
        return id(call(post("/equipments"), json(Map.of("projectId", DEMO_PROJECT, "equipmentCode", code, "equipmentName",
            code.toLowerCase(), "equipmentType", "machine"))), "equipmentId");
    }

    private void stock(String itemId, double quantity, String tag) throws Exception {
        stock(DEMO_PROJECT, itemId, quantity, tag);
    }

    private void stock(String project, String itemId, double quantity, String tag) throws Exception {
        call(post("/inventories"), json(Map.of("projectId", project, "itemId", itemId, "quantity", quantity,
            "location", "PL-" + tag))).andExpect(status().isOk());
    }

    private String item(String code, String unitId) throws Exception {
        return item(DEMO_PROJECT, code, unitId);
    }

    private String item(String project, String code, String unitId) throws Exception {
        return id(call(post("/items"), json(Map.of("projectId", project, "itemCode", code, "itemName", code.toLowerCase(),
            "unitId", unitId))), "itemId");
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String id(ResultActions result, String field) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
            .path("data").path(field).asText();
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
