package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
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

/** Putaway and pick tasks (docs/domain/warehouse-task.md) against real Postgres. */
@AutoConfigureMockMvc
class WarehouseTaskIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void aPutawayPlansAMoveAndDoingItTransfersTheStock() throws Exception {
        String tag = tag();
        String bolt = item("WT-BOLT-" + tag, "unit_ea", false);
        String dock = "DOCK-" + tag;
        String shelf = "SHELF-" + tag;
        String record = id(call(post("/inventories"), json(Map.of("projectId", DEMO_PROJECT, "itemId", bolt, "quantity", 10,
            "location", dock))), "inventoryId");

        JsonNode task = data(putaway(record, "6", shelf)
            .andExpect(jsonPath("$.data.taskNo").value(org.hamcrest.Matchers.matchesPattern("WT-\\d{4}")))
            .andExpect(jsonPath("$.data.status").value("open"))
            .andExpect(jsonPath("$.data.fromLocation").value(dock)));
        putaway(record, "5", shelf)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("Only 4 of this record is free to move")));
        putaway(record, "1", dock.toLowerCase())
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("already at")));

        // Planning moves nothing; doing it is a transfer.
        call(get("/inventories/" + record)).andExpect(jsonPath("$.data.quantity").value(10));
        String taskId = task.path("taskId").asText();
        call(post("/warehouse-tasks/" + taskId + "/complete"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("done"))
            .andExpect(jsonPath("$.data.transferId").isNotEmpty());
        call(get("/inventories/" + record)).andExpect(jsonPath("$.data.quantity").value(4));
        call(post("/warehouse-tasks/" + taskId + "/complete")).andExpect(status().isConflict());
        call(post("/warehouse-tasks/" + taskId + "/cancel"), "{\"reason\":\"x\"}").andExpect(status().isConflict());

        String second = id(putaway(record, "4", shelf), "taskId");
        call(post("/warehouse-tasks/" + second + "/cancel"), "{}").andExpect(status().isBadRequest());
        call(post("/warehouse-tasks/" + second + "/cancel"), "{\"reason\":\"Shelf is full\"}")
            .andExpect(jsonPath("$.data.status").value("cancelled"));
        call(get("/warehouse-tasks").param("projectId", DEMO_PROJECT).param("status", "open"))
            .andExpect(jsonPath("$.data[?(@.taskId == '" + second + "')]").isEmpty());
        callAs("unrelated-user", get("/warehouse-tasks").param("projectId", DEMO_PROJECT)).andExpect(status().isForbidden());
    }

    @Test
    void aPickListTakesAWorkOrdersMaterialsFirstExpiringFirst() throws Exception {
        String tag = tag();
        String bread = item("WT-BREAD-" + tag, "unit_ea", false);
        String flour = item("WT-FLOUR-" + tag, "unit_kg", true);
        String salt = item("WT-SALT-" + tag, "unit_kg", false);
        String store = "STORE-" + tag;
        String line = "LINE-" + tag;
        String later = lot(flour, "L-LATE-" + tag, LocalDate.now().plusDays(60));
        String sooner = lot(flour, "L-SOON-" + tag, LocalDate.now().plusDays(30));
        String laterStock = id(call(post("/inventories"), json(Map.of("projectId", DEMO_PROJECT, "itemId", flour, "quantity", 6,
            "location", store, "lotId", later))), "inventoryId");
        String soonerStock = id(call(post("/inventories"), json(Map.of("projectId", DEMO_PROJECT, "itemId", flour, "quantity", 6,
            "location", store, "lotId", sooner))), "inventoryId");
        String saltStock = id(call(post("/inventories"), json(Map.of("projectId", DEMO_PROJECT, "itemId", salt, "quantity", 1,
            "location", store))), "inventoryId");
        String bomId = id(call(post("/boms"), json(Map.of("projectId", DEMO_PROJECT, "targetItemId", bread, "bomName", "Bread " + tag,
            "baseQuantity", 10, "baseUnit", "ea"))), "bomId");
        call(post("/boms/" + bomId + "/lines"), json(Map.of("childItemId", flour, "quantity", 5, "unit", "kg"))).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/lines"), json(Map.of("childItemId", salt, "quantity", 200, "unit", "g"))).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/submit")).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/approve")).andExpect(status().isOk());
        String order = id(call(post("/work-orders"), json(Map.of("projectId", DEMO_PROJECT, "workOrderTitle", "Pick " + tag,
            "workflowId", DEMO_WORKFLOW, "targetQuantity", 20, "bomId", bomId))), "workOrderId");
        String draft = id(call(post("/work-orders"), json(Map.of("projectId", DEMO_PROJECT, "workOrderTitle", "Draft " + tag,
            "workflowId", DEMO_WORKFLOW, "targetQuantity", 20, "bomId", bomId))), "workOrderId");
        call(post("/work-orders/" + order + "/approve")).andExpect(status().isOk());

        pickList(Map.of("projectId", DEMO_PROJECT, "workOrderId", draft, "stagingLocation", line)).andExpect(status().isConflict());

        // 20 breads need 10 kg flour (sooner LOT first) and 0.4 kg salt.
        JsonNode picked = data(pickList(Map.of("projectId", DEMO_PROJECT, "workOrderId", order, "stagingLocation", line))
            .andExpect(jsonPath("$.data.tasks.length()").value(3))
            .andExpect(jsonPath("$.data.tasks[0].inventoryId").value(soonerStock))
            .andExpect(jsonPath("$.data.tasks[0].quantity").value(6))
            .andExpect(jsonPath("$.data.tasks[1].inventoryId").value(laterStock))
            .andExpect(jsonPath("$.data.tasks[1].quantity").value(4))
            .andExpect(jsonPath("$.data.tasks[2].inventoryId").value(saltStock))
            .andExpect(jsonPath("$.data.tasks[2].quantity").value(0.4))
            .andExpect(jsonPath("$.data.tasks[0].workOrderId").value(order))
            .andExpect(jsonPath("$.data.lines[0].plannedNow").value(10))
            .andExpect(jsonPath("$.data.lines[0].shortage").value(0)));

        // Asking again plans nothing more: the open picks already cover the order.
        pickList(Map.of("projectId", DEMO_PROJECT, "workOrderId", order, "stagingLocation", line))
            .andExpect(jsonPath("$.data.tasks.length()").value(0))
            .andExpect(jsonPath("$.data.lines[0].alreadyPlanned").value(10));

        // Doing a pick moves the stock; what is at the line then counts as picked.
        call(post("/warehouse-tasks/" + picked.path("tasks").get(0).path("taskId").asText() + "/complete")).andExpect(status().isOk());
        pickList(Map.of("projectId", DEMO_PROJECT, "workOrderId", order, "stagingLocation", line))
            .andExpect(jsonPath("$.data.tasks.length()").value(0))
            .andExpect(jsonPath("$.data.lines[0].atStaging").value(6))
            .andExpect(jsonPath("$.data.lines[0].alreadyPlanned").value(4));

        // Lines without an order: salt has 0.6 kg left to plan, so 4 kg of 5 are short.
        pickList(Map.of("projectId", DEMO_PROJECT, "stagingLocation", line, "lines", List.of(Map.of("itemId", salt, "quantity", 5))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.tasks.length()").value(1))
            .andExpect(jsonPath("$.data.tasks[0].quantity").value(0.6))
            .andExpect(jsonPath("$.data.lines[0].shortage").value(4));
        pickList(Map.of("projectId", DEMO_PROJECT, "stagingLocation", line)).andExpect(status().isBadRequest());
        call(get("/warehouse-tasks").param("projectId", DEMO_PROJECT).param("workOrderId", order))
            .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    void partOfATaskIsDoneAsATaskOfItsOwnAndTheRestStaysOpen() throws Exception {
        String tag = tag();
        String nut = item("WT-NUT-" + tag, "unit_ea", false);
        String dock = "DOCK-" + tag;
        String shelf = "SHELF-" + tag;
        String record = id(call(post("/inventories"), json(Map.of("projectId", DEMO_PROJECT, "itemId", nut, "quantity", 10,
            "location", dock))), "inventoryId");
        JsonNode task = data(putaway(record, "6", shelf));
        String taskId = task.path("taskId").asText();
        String taskNo = task.path("taskNo").asText();

        call(post("/warehouse-tasks/" + taskId + "/complete"), "{\"quantity\":0}").andExpect(status().isBadRequest());
        call(post("/warehouse-tasks/" + taskId + "/complete"), "{\"quantity\":7}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("at most 6")));

        // 2 of 6 now: a done task of its own, the transfer of 2, and the first task open with 4.
        JsonNode part = data(call(post("/warehouse-tasks/" + taskId + "/complete"), "{\"quantity\":2}")
            .andExpect(jsonPath("$.data.status").value("done"))
            .andExpect(jsonPath("$.data.quantity").value(2))
            .andExpect(jsonPath("$.data.note").value("Part of " + taskNo))
            .andExpect(jsonPath("$.data.fromLocation").value(dock))
            .andExpect(jsonPath("$.data.toLocation").value(shelf))
            .andExpect(jsonPath("$.data.transferId").isNotEmpty()));
        assertThat(part.path("taskNo").asText()).isNotEqualTo(taskNo).matches("WT-\\d{4}");
        call(get("/inventories/" + record)).andExpect(jsonPath("$.data.quantity").value(8));
        JsonNode rest = null;
        for (JsonNode one : data(call(get("/warehouse-tasks").param("projectId", DEMO_PROJECT).param("status", "open")))) {
            if (taskId.equals(one.path("taskId").asText())) {
                rest = one;
            }
        }
        assertThat(rest).isNotNull();
        assertThat(rest.path("quantity").decimalValue()).isEqualByComparingTo("4");

        // The rest without a quantity, then a whole task given its own quantity: no part is split off.
        call(post("/warehouse-tasks/" + taskId + "/complete"))
            .andExpect(jsonPath("$.data.taskId").value(taskId))
            .andExpect(jsonPath("$.data.status").value("done"));
        call(get("/inventories/" + record)).andExpect(jsonPath("$.data.quantity").value(4));
        String whole = id(putaway(record, "3", shelf), "taskId");
        call(post("/warehouse-tasks/" + whole + "/complete"), "{\"quantity\":3}").andExpect(jsonPath("$.data.taskId").value(whole));
    }

    @Test
    void anOpenTaskIsGivenToAProjectMember() throws Exception {
        String tag = tag();
        String washer = item("WT-WASHER-" + tag, "unit_ea", false);
        String record = id(call(post("/inventories"), json(Map.of("projectId", DEMO_PROJECT, "itemId", washer, "quantity", 5,
            "location", "DOCK-" + tag))), "inventoryId");
        String taskId = id(putaway(record, "5", "SHELF-" + tag), "taskId");
        String assignee = "/warehouse-tasks/" + taskId + "/assignee";

        call(put(assignee), "{\"assignedTo\":\"unrelated-user\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("unrelated-user is not a member of this project."));
        call(put(assignee), "{\"assignedTo\":\" " + DEMO_OWNER + " \"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.assignedTo").value(DEMO_OWNER));
        call(get("/warehouse-tasks").param("projectId", DEMO_PROJECT).param("assignedTo", DEMO_OWNER))
            .andExpect(jsonPath("$.data[?(@.taskId == '" + taskId + "')]").isNotEmpty());
        call(get("/warehouse-tasks").param("projectId", DEMO_PROJECT).param("assignedTo", "someone-else"))
            .andExpect(jsonPath("$.data[?(@.taskId == '" + taskId + "')]").isEmpty());
        callAs("unrelated-user", put(assignee).contentType(MediaType.APPLICATION_JSON).content("{\"assignedTo\":null}"))
            .andExpect(status().isForbidden());

        // A part done keeps the assignee (W6); blank leaves the rest to no one, and a done task cannot be given.
        call(post("/warehouse-tasks/" + taskId + "/complete"), "{\"quantity\":2}")
            .andExpect(jsonPath("$.data.assignedTo").value(DEMO_OWNER));
        call(put(assignee), "{\"assignedTo\":\"\"}").andExpect(jsonPath("$.data.assignedTo").doesNotExist());
        call(post("/warehouse-tasks/" + taskId + "/complete")).andExpect(jsonPath("$.data.status").value("done"));
        call(put(assignee), "{\"assignedTo\":\"" + DEMO_OWNER + "\"}").andExpect(status().isConflict());
    }

    private ResultActions putaway(String record, String quantity, String to) throws Exception {
        return call(post("/warehouse-tasks"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"inventoryId\":\"" + record
            + "\",\"quantity\":" + quantity + ",\"toLocation\":\"" + to + "\"}");
    }

    private ResultActions pickList(Map<String, Object> body) throws Exception {
        return call(post("/warehouse-tasks/pick-list"), json(body));
    }

    private String lot(String itemId, String lotNo, LocalDate expiry) throws Exception {
        return id(call(post("/lots"), json(Map.of("projectId", DEMO_PROJECT, "itemId", itemId, "lotNo", lotNo,
            "expiryDate", expiry.toString()))), "lotId");
    }

    private String item(String code, String unitId, boolean lotTracked) throws Exception {
        return id(call(post("/items"), json(Map.of("projectId", DEMO_PROJECT, "itemCode", code, "itemName", code.toLowerCase(),
            "unitId", unitId, "lotManageYn", lotTracked ? "Y" : "N"))), "itemId");
    }

    private static String tag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
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
