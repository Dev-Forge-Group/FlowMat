package org.myweb.flowmat;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

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
            json(Map.of("target", Map.of("processIoId", packOut, "quantity", 1000, "unit", "ea"), "startAt", "2030-01-07T09:00:00Z")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.revisionId").value(revision))
            // In time (equipment without a calendar works around the clock): Mix 55.2169 min from 09:00, then Fill on the
            // filler 50.5051 min, then Pack 41.6667 min.
            .andExpect(jsonPath("$.data.nodes[?(@.processId == '" + mix + "')].endAt").value(hasItem(org.hamcrest.Matchers.startsWith("2030-01-07T09:55:13.014"))))
            .andExpect(jsonPath("$.data.nodes[?(@.processId == '" + fill + "')].startAt").value(hasItem(org.hamcrest.Matchers.startsWith("2030-01-07T09:55:13.014"))))
            .andExpect(jsonPath("$.data.finishAt").value(org.hamcrest.Matchers.startsWith("2030-01-07T11:27:23.322")))
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

    /**
     * A step that names a person waits for their shift (ADR-043 decision 5), and a working-time delay before it counts their
     * working time: kim works 10:00-11:00, the delay is 10 min and the step 30 min, so it runs 10:10-10:40.
     */
    @Test
    void aStepWithANamedPersonWaitsForTheirShift() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String zone = objectMapper.readTree(call(get("/projects/" + DEMO_PROJECT + "/time-zone")).andReturn().getResponse()
            .getContentAsString()).path("data").path("timeZone").asText();
        String kim = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", kim, kim + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), DEMO_PROJECT, kim, "editor");
        call(put("/projects/" + DEMO_PROJECT + "/workers/" + kim + "/shifts"), json(Map.of("shifts",
            java.util.List.of(Map.of("shiftStart", "10:00", "shiftEnd", "11:00", "workDays", java.util.List.of(1, 2, 3, 4, 5, 6, 7))))))
            .andExpect(status().isOk());
        String part = item("PL-PART-" + tag, "unit_ea");
        String stool = item("PL-STOOL-" + tag, "unit_ea");
        Graph graph = new Graph("Crew " + tag);
        String prepare = graph.node("Prepare");
        String assemble = graph.node("Assemble");
        String prepared = graph.port(prepare, "output", part, 1, "ea");
        String takes = graph.port(assemble, "input", part, 1, "ea");
        String out = graph.port(assemble, "output", stool, 1, "ea");
        // Ten minutes to settle, counted in kim's working time (ADR-022): from 10:00 to 10:10.
        call(post("/process-connections"), json(Map.of("workflowId", graph.workflow, "fromProcessId", prepare, "toProcessId", assemble,
            "fromIoId", prepared, "toIoId", takes, "delayTimeSec", 600, "delayBasis", "working"))).andExpect(status().isOk());
        requirements(assemble, Map.of("kind", "crew", "ref", kim, "amount", 1), Map.of("kind", "time", "amount", 30));
        graph.publish();
        java.time.ZonedDateTime morning = java.time.LocalDate.of(2030, 1, 7).atTime(8, 0).atZone(java.time.ZoneId.of(zone));
        var plan = objectMapper.readTree(call(post("/workflows/" + graph.workflow + "/plan-previews"), json(Map.of(
                "target", Map.of("processIoId", out, "quantity", 1), "startAt", morning.toOffsetDateTime().toString())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        JsonNode step = null;
        for (JsonNode one : plan.path("nodes")) if (one.path("processId").asText().equals(assemble)) step = one;
        org.assertj.core.api.Assertions.assertThat(java.time.OffsetDateTime.parse(step.path("startAt").asText()).toInstant())
            .isEqualTo(morning.withHour(10).withMinute(10).toInstant());
        org.assertj.core.api.Assertions.assertThat(java.time.OffsetDateTime.parse(step.path("endAt").asText()).toInstant())
            .isEqualTo(morning.withHour(10).withMinute(40).toInstant());
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
        // Flour comes in 25 kg bags: the 10 kg short is ordered as one bag (PO12).
        String flour = id(call(post("/items"), json(Map.of("projectId", project, "itemCode", "PC-FLOUR-" + tag, "itemName", "flour",
            "unitId", "unit_kg", "purchaseUnit", "bag", "purchaseUnitQty", 25))), "itemId");
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
            .andExpect(jsonPath("$.data.workflowRevisionId").value(plan.path("revisionId").asText()))
            .andExpect(jsonPath("$.data.targetItemId").value(bread))
            .andExpect(jsonPath("$.data.targetQuantity").value(10.0));
        call(get("/purchase-orders/" + order)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("draft"))
            .andExpect(jsonPath("$.data.lines[0].itemId").value(flour))
            .andExpect(jsonPath("$.data.lines[0].quantity").value(25.0));
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

    /**
     * Plan against actual (docs/domain/plan-vs-actual.md): 10 loaves need 20 kg of flour and 30 minutes of baking; a run makes
     * 5 of them with 10.6 kg in 16 minutes, so the plan for 5 is 10 kg (+6.0%, outside 5%) and 15 minutes (+6.7%, inside 15%).
     */
    @Test
    void aConfirmedPlanIsComparedWithItsFinishedRuns() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String project = id(call(post("/projects"), json(Map.of("projectName", "Plan actual " + tag, "ownerId", DEMO_OWNER))), "projectId");
        String flour = item(project, "PA-FLOUR-" + tag, "unit_kg");
        String bread = item(project, "PA-BREAD-" + tag, "unit_ea");
        String flourStock = id(call(post("/inventories"), json(Map.of("projectId", project, "itemId", flour, "quantity", 30,
            "location", "PA-" + tag))), "inventoryId");
        Graph graph = new Graph(project, "Bread " + tag);
        String bake = graph.node("Bake");
        graph.port(bake, "input", flour, 2, "kg");
        String loaves = graph.port(bake, "output", bread, 1, "ea");
        requirements(bake, Map.of("kind", "time", "amount", 3, "unit", "min", "per", "output_unit"));
        graph.publish();
        Map<String, Object> target = Map.of("processIoId", loaves, "quantity", 10, "unit", "ea");
        var plan = objectMapper.readTree(call(post("/workflows/" + graph.workflow + "/plan-previews"), json(Map.of("target", target)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        String workOrder = id(call(post("/workflows/" + graph.workflow + "/plan-confirmations"), json(Map.of("target", target,
            "basis", objectMapper.convertValue(plan.path("basis"), Map.class), "requestId", "pa-" + tag))), "workOrderId");
        String actuals = "/work-orders/" + workOrder + "/plan-actuals";
        call(get(actuals)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.finishedRuns").value(0))
            .andExpect(jsonPath("$.data.materials[0].plannedForTarget").value(20.0))
            .andExpect(jsonPath("$.data.materials[0].planned").doesNotExist());

        call(post("/work-orders/" + workOrder + "/approve")).andExpect(status().isOk());
        String run = id(call(post("/production-runs/start"), json(Map.of("projectId", project, "workflowId", graph.workflow,
            "plannedOutputQty", 5, "workOrderId", workOrder))), "productionRunId");
        call(post("/production-runs/" + run + "/items"), json(Map.of("itemId", flour, "inventoryId", flourStock, "processId", bake,
            "direction", "input", "unit", "kg", "plannedQty", 10, "actualQty", 10.6))).andExpect(status().isOk());
        // The bake step took 16 minutes; set on the step itself, as the clock of a test cannot wait.
        jdbc.update("update flow_run_step set status = 'completed', started_at = timestamptz '2030-01-07 09:00:00+00', "
            + "ended_at = timestamptz '2030-01-07 09:16:00+00' where flow_run_id in "
            + "(select flow_run_id from flow_run where production_run_id = ?)", run);
        call(post("/production-runs/" + run + "/finish"), json(Map.of("actualOutputQty", 5))).andExpect(status().isOk());

        String material = "$.data.materials[?(@.itemId == '" + flour + "')]";
        String step = "$.data.steps[?(@.processId == '" + bake + "')]";
        call(get(actuals)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.target").value(10.0))
            .andExpect(jsonPath("$.data.output").value(5.0))
            .andExpect(jsonPath("$.data.finishedRuns").value(1))
            .andExpect(jsonPath("$.data.complete").value(true))
            .andExpect(jsonPath(material + ".planned").value(hasItem(10.0)))
            .andExpect(jsonPath(material + ".used").value(hasItem(10.6)))
            .andExpect(jsonPath(material + ".variancePercent").value(hasItem(6.0)))
            .andExpect(jsonPath(material + ".within").value(hasItem(false)))
            .andExpect(jsonPath(step + ".plannedForTarget").value(hasItem(30.0)))
            .andExpect(jsonPath(step + ".planned").value(hasItem(15.0)))
            .andExpect(jsonPath(step + ".actual").value(hasItem(16.0)))
            .andExpect(jsonPath(step + ".variancePercent").value(hasItem(6.7)))
            .andExpect(jsonPath(step + ".within").value(hasItem(true)));
        call(get("/workflows/" + graph.workflow + "/plan-confirmations")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].workOrderId").value(workOrder))
            .andExpect(jsonPath("$.data.length()").value(1));

        // Someone outside the project sees neither; a work order made by hand has no plan to compare with.
        mockMvc.perform(get(actuals).header("Authorization", "Bearer " + jwtProvider.generateAccessToken("plan-outsider")))
            .andExpect(status().isForbidden());
        String byHand = id(call(post("/work-orders"), json(Map.of("projectId", project, "workOrderTitle", "by hand " + tag))),
            "workOrderId");
        call(get("/work-orders/" + byHand + "/plan-actuals")).andExpect(status().isNotFound());
    }

    /**
     * Resources first class (docs/architecture/adr/ADR-046-resources-first-class.md): 10 loaves need 10 kg of dough at 2.5,
     * 30 kWh of electricity at 0.2 and 20 l of water without a price. Electricity and water are not stocked: no shortage, no
     * stock limit, no draft purchase; the plan costs 25 + 6 = 31 with one row unknown. Against the factory's limits, the energy
     * is above 10 kWh an hour and the water within 100 l a day.
     */
    @Test
    void energyAndWaterAreDrawnNotStockedAndThePlanCostsWhatItKnows() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String project = id(call(post("/projects"), json(Map.of("projectName", "Plan resources " + tag, "ownerId", DEMO_OWNER))), "projectId");
        String kwh = "kwh" + tag.toLowerCase();
        String kwhId = id(call(post("/units"), json(Map.of("unitCode", kwh, "unitName", "kilowatt hour " + tag, "unitType", "energy"))),
            "unitId");
        String dough = id(call(post("/items"), json(Map.of("projectId", project, "itemCode", "PR-DOUGH-" + tag, "itemName", "dough",
            "unitId", "unit_kg", "unitCost", 2.5))), "itemId");
        String power = id(call(post("/items"), json(Map.of("projectId", project, "itemCode", "PR-KWH-" + tag, "itemName", "electricity",
            "unitId", kwhId, "unitCost", 0.2))), "itemId");
        String water = item(project, "PR-WATER-" + tag, "unit_l");
        String bread = item(project, "PR-BREAD-" + tag, "unit_ea");
        stock(project, dough, 5, tag);
        stock(project, power, 1000, tag + "P");
        call(post("/suppliers"), json(Map.of("projectId", project, "supplierName", "Mill " + tag))).andExpect(status().isOk());
        Graph graph = new Graph(project, "Bread " + tag);
        String bake = graph.node("Bake");
        graph.port(bake, "input", dough, 1, "kg");
        String energyPort = id(call(post("/process-ios"), json(Map.of("processId", bake, "direction", "input", "itemId", power,
            "quantity", 3, "unit", kwh, "resourceType", "energy", "requiredYn", "N"))), "processIoId");
        call(post("/process-ios"), json(Map.of("processId", bake, "direction", "input", "itemId", water, "quantity", 2, "unit", "l",
            "resourceType", "water", "requiredYn", "N"))).andExpect(status().isOk());
        String loaves = graph.port(bake, "output", bread, 1, "ea");
        // 3 minutes a loaf: 10 loaves take half an hour, so 30 kWh averages 60 kWh per hour.
        requirements(bake, Map.of("kind", "time", "amount", 3, "unit", "min", "per", "output_unit"));
        graph.publish();
        // The factory's limits (decision 5): its owner only, energy or water.
        String limits = "/projects/" + project + "/resource-capacities";
        Map<String, Object> energyLimit = Map.of("resourceType", "energy", "unit", kwh, "amount", 10, "per", "hour");
        mockMvc.perform(put(limits).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("capacities", List.of(energyLimit))))
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("plan-outsider")))
            .andExpect(status().isForbidden());
        call(put(limits), json(Map.of("capacities", List.of(Map.of("resourceType", "steam", "unit", "kg", "amount", 1, "per", "hour")))))
            .andExpect(status().isBadRequest());
        call(put(limits), json(Map.of("capacities", List.of(energyLimit,
                Map.of("resourceType", "water", "unit", "l", "amount", 100, "per", "day")))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        call(get(limits)).andExpect(jsonPath("$.data[?(@.resourceType == 'energy')].amount").value(hasItem(10.0)));

        Map<String, Object> target = Map.of("processIoId", loaves, "quantity", 10, "unit", "ea");
        String row = "$.data.requirements[?(@.itemId == '%s')]";
        var plan = objectMapper.readTree(call(post("/workflows/" + graph.workflow + "/plan-previews"), json(Map.of("target", target)))
            .andExpect(status().isOk())
            .andExpect(jsonPath(row.formatted(dough) + ".resourceType").value(hasItem("material")))
            .andExpect(jsonPath(row.formatted(dough) + ".shortage").value(hasItem(5.0)))
            .andExpect(jsonPath(row.formatted(dough) + ".cost").value(hasItem(25.0)))
            .andExpect(jsonPath(row.formatted(power) + ".resourceType").value(hasItem("energy")))
            .andExpect(jsonPath(row.formatted(power) + ".at").value(hasItem(energyPort)))
            .andExpect(jsonPath(row.formatted(power) + ".needed").value(hasItem(30.0)))
            .andExpect(jsonPath(row.formatted(power) + ".available").value(hasItem(org.hamcrest.Matchers.nullValue())))
            .andExpect(jsonPath(row.formatted(power) + ".shortage").value(hasItem(org.hamcrest.Matchers.nullValue())))
            .andExpect(jsonPath(row.formatted(power) + ".cost").value(hasItem(6.0)))
            .andExpect(jsonPath(row.formatted(water) + ".resourceType").value(hasItem("water")))
            .andExpect(jsonPath(row.formatted(water) + ".cost").value(hasItem(org.hamcrest.Matchers.nullValue())))
            .andExpect(jsonPath("$.data.limits[?(@.kind == 'stock')]").value(org.hamcrest.Matchers.hasSize(1)))
            // 60 kWh an hour is above the 10 the factory has; 20 l in a day is within 100.
            .andExpect(jsonPath("$.data.limits[?(@.kind == 'resource-capacity')].at").value(org.hamcrest.Matchers.contains("energy")))
            .andExpect(jsonPath("$.data.limits[?(@.kind == 'resource-capacity')].message").value(hasItem(
                "Energy averages 60 " + kwh + " per hour over the plan, above the factory's 10 " + kwh + " per hour.")))
            .andExpect(jsonPath("$.data.feasible").value(true))
            .andExpect(jsonPath("$.data.buildable").value(5))
            .andExpect(jsonPath("$.data.resources[?(@.resourceType == 'energy')].needed").value(hasItem(30.0)))
            .andExpect(jsonPath("$.data.resources[?(@.resourceType == 'energy')].cost").value(hasItem(6.0)))
            .andExpect(jsonPath("$.data.resources[?(@.resourceType == 'water')].needed").value(hasItem(20.0)))
            .andExpect(jsonPath("$.data.costs[?(@.resourceType == 'water')].unknown").value(hasItem(1)))
            .andExpect(jsonPath("$.data.totalCost").value(31.0))
            .andExpect(jsonPath("$.data.basis.available['" + power + "']").doesNotExist())
            .andReturn().getResponse().getContentAsString()).path("data");

        // Confirming orders the dough that is short, not the electricity.
        var made = objectMapper.readTree(call(post("/workflows/" + graph.workflow + "/plan-confirmations"), json(Map.of("target", target,
            "basis", objectMapper.convertValue(plan.path("basis"), Map.class), "requestId", "pr-" + tag, "supplierName", "Mill " + tag)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        call(get("/purchase-orders/" + made.path("purchaseOrderIds").path(0).asText())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lines.length()").value(1))
            .andExpect(jsonPath("$.data.lines[0].itemId").value(dough));
    }

    /**
     * A recycling loop planned through (docs/architecture/adr/ADR-048-plan-through-loops.md): Cut passes what arrives, a Split
     * sends 20% of it to Regrind (0.9 kg out of 1 kg) and back to Cut, the rest to Pack. 18% comes back each round, so 1,000
     * parts need 1,250 kg through Cut but only 1,025 kg of raw material over ten rounds.
     */
    @Test
    void aRecyclingLoopIsPlannedWithWhatComesBack() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String raw = item("PL-RAW-" + tag, "unit_kg");
        String part = item("PL-PART-" + tag, "unit_ea");
        stock(raw, 2000, tag);
        Graph graph = new Graph("Recycle " + tag);
        String receive = graph.node("Receive");
        String cut = graph.node("Cut");
        String offcuts = graph.gateway("Offcuts back", "split");
        String regrind = graph.node("Regrind");
        String pack = graph.node("Pack");
        String receiveIn = graph.port(receive, "input", raw, 1, "kg");
        String receiveOut = graph.port(receive, "output", raw, 1, "kg");
        String regrindIn = graph.port(regrind, "input", raw, 1, "kg");
        String regrindOut = graph.port(regrind, "output", raw, 0.9, "kg");
        String packIn = graph.port(pack, "input", raw, 1, "kg");
        String packOut = graph.port(pack, "output", part, 1, "ea");
        graph.link(receive, receiveOut, cut, null, null, null);
        graph.link(cut, null, offcuts, null, null, null);
        graph.link(offcuts, null, regrind, regrindIn, "share", 20);
        String back = graph.link(regrind, regrindOut, cut, null, null, null);
        graph.link(offcuts, null, pack, packIn, "rest", null);
        graph.publish();

        String row = "$.data.requirements[?(@.itemId == '%s')]";
        call(post("/workflows/" + graph.workflow + "/plan-previews"),
            json(Map.of("target", Map.of("processIoId", packOut, "quantity", 1000, "unit", "ea"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.feasible").value(true))
            .andExpect(jsonPath("$.data.requirements.length()").value(1))
            .andExpect(jsonPath(row.formatted(raw) + ".at").value(hasItem(receiveIn)))
            .andExpect(jsonPath(row.formatted(raw) + ".needed").value(hasItem(1025.0001)))
            .andExpect(jsonPath("$.data.nodes[?(@.processId == '" + cut + "')].through").value(hasItem(1250.0)))
            .andExpect(jsonPath("$.data.nodes[?(@.processId == '" + regrind + "')].batches").value(hasItem(250.0)))
            .andExpect(jsonPath("$.data.assumptions[?(@.kind == 'loop')].value")
                .value(hasItem("18% of what passes comes back by " + back + ", for up to 10 rounds")));
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

        String gateway(String name, String type) throws Exception {
            return id(call(post("/processes"), json(Map.of("workflowId", workflow, "processName", name, "nodeType", type))),
                "processId");
        }

        /** A connection that may leave or reach a node without a port, or carry a Split's share. */
        String link(String from, String fromIo, String to, String toIo, String splitKind, Integer splitValue) throws Exception {
            Map<String, Object> body = new HashMap<>(Map.of("workflowId", workflow, "fromProcessId", from, "toProcessId", to));
            if (fromIo != null) body.put("fromIoId", fromIo);
            if (toIo != null) body.put("toIoId", toIo);
            if (splitKind != null) body.put("splitKind", splitKind);
            if (splitValue != null) body.put("splitValue", splitValue);
            return id(call(post("/process-connections"), json(body)), "connectionId");
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
