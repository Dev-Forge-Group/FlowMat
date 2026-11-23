package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.domain.catalog.repository.ItemRepository;
import org.myweb.flowmat.domain.project.domain.entity.ProjectMember;
import org.myweb.flowmat.domain.project.repository.ProjectMemberRepository;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Corrections of finished runs (docs/domain/production-run-correction.md) against real Postgres. */
@AutoConfigureMockMvc
class RunCorrectionIntegrationTest extends IntegrationTestSupport {

    private static final String EDITOR = "correction-editor";
    private static final String VIEWER = "correction-viewer";
    private static final String OUTSIDER = "correction-outsider";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ItemRepository itemRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;

    @Test
    void swappingTheInputLotMovesStockRecordingsAndGenealogyTogether() throws Exception {
        String raw = item(true);
        String product = item(true);
        String lotA = createLot(raw);
        String lotB = createLot(raw);
        String lotP = createLot(product);
        String stockA = id(createStock(raw, lotA, "20"), "inventoryId");
        String stockB = id(createStock(raw, lotB, "20"), "inventoryId");
        String stockP = id(createStock(product, lotP, "0"), "inventoryId");

        String runId = startRun();
        String wrongInput = id(record(runId, stockA, raw, "input", "5"), "productionRunItemId");
        record(runId, stockP, product, "output", "4");
        finish(runId, "4");

        String correctionId = id(request(runId, "{\"reason\":\"Wrong LOT picked\",\"lines\":["
            + "{\"kind\":\"void_item\",\"targetRunItemId\":\"" + wrongInput + "\"},"
            + "{\"kind\":\"add_item\",\"direction\":\"input\",\"itemId\":\"" + raw + "\",\"inventoryId\":\"" + stockB
            + "\",\"qty\":5,\"unit\":\"kg\"}]}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("pending_approval"))
            .andExpect(jsonPath("$.data.correctionNo").value(1)), "productionRunCorrectionId");
        // Nothing moves until it is approved.
        call(get("/inventories/" + stockA)).andExpect(jsonPath("$.data.quantity").value(15));

        call(post("/production-runs/" + runId + "/corrections/" + correctionId + "/approve"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("applied"))
            .andExpect(jsonPath("$.data.decidedBy").value(DEMO_OWNER))
            .andExpect(jsonPath("$.data.appliedAt").value(notNullValue()))
            .andExpect(jsonPath("$.data.lines[1].createdRunItemId").value(notNullValue()));

        call(get("/inventories/" + stockA)).andExpect(jsonPath("$.data.quantity").value(20));
        call(get("/inventories/" + stockB)).andExpect(jsonPath("$.data.quantity").value(15));
        call(get("/lots/" + lotP + "/trace"))
            .andExpect(jsonPath("$.data.nodes[*].lot.lotId").value(containsInAnyOrder(lotB)));
        call(get("/production-runs/" + runId + "/items"))
            .andExpect(jsonPath("$.data[?(@.productionRunItemId == '" + wrongInput + "')].cancelled").value(true))
            .andExpect(jsonPath("$.data[?(@.productionRunItemId == '" + wrongInput + "')].cancelledByCorrectionId")
                .value(correctionId))
            .andExpect(jsonPath("$.data[?(@.quantitySource == 'correction')].productionRunCorrectionId").value(correctionId));
        call(get("/production-runs/" + runId + "/corrections"))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].lines.length()").value(2));

        // Applied once only.
        call(post("/production-runs/" + runId + "/corrections/" + correctionId + "/approve"))
            .andExpect(status().isConflict());
    }

    @Test
    void theOutputQuantityCanBeCorrected() throws Exception {
        String runId = startRun();
        record(runId, null, item(false), "input", "1");
        finish(runId, "1");

        String correctionId = id(request(runId, "{\"reason\":\"Miscounted\",\"lines\":[{\"kind\":\"set_output_qty\",\"afterQty\":3}]}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lines[0].beforeQty").value(1.0)), "productionRunCorrectionId");
        call(post("/production-runs/" + runId + "/corrections/" + correctionId + "/approve")).andExpect(status().isOk());
        call(get("/production-runs/" + runId)).andExpect(jsonPath("$.data.actualOutputQty").value(3.0));
    }

    @Test
    void anOutputAlreadyUsedByALaterRunPointsToThatRun() throws Exception {
        String product = item(true);
        String lotP = createLot(product);
        String stockP = id(createStock(product, lotP, "0"), "inventoryId");
        String run1 = startRun();
        String output = id(record(run1, stockP, product, "output", "4"), "productionRunItemId");
        finish(run1, "4");
        String run2 = startRun();
        record(run2, stockP, product, "input", "4");
        String run2Number = objectMapper.readTree(call(get("/production-runs/" + run2)).andReturn().getResponse()
            .getContentAsString()).path("data").path("runNumber").asText();

        String correctionId = id(request(run1, "{\"reason\":\"Never made\",\"lines\":[{\"kind\":\"void_item\",\"targetRunItemId\":\""
            + output + "\"}]}").andExpect(status().isOk()), "productionRunCorrectionId");
        call(post("/production-runs/" + run1 + "/corrections/" + correctionId + "/approve"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("by run " + run2Number + "; correct that run first")));

        // Nothing changed: the correction still waits and the output stands.
        call(get("/production-runs/" + run1 + "/corrections")).andExpect(jsonPath("$.data[0].status").value("pending_approval"));
        call(get("/production-runs/" + run1 + "/items")).andExpect(jsonPath("$.data[0].cancelled").value(false));
        call(get("/inventories/" + stockP)).andExpect(jsonPath("$.data.quantity").value(0));
    }

    @Test
    void requestsAreCheckedAndOnlyTheOwnerDecides() throws Exception {
        String material = item(false);
        String runId = startRun();
        String item = id(record(runId, null, material, "input", "1"), "productionRunItemId");
        request(runId, "{\"reason\":\"x\",\"lines\":[{\"kind\":\"void_item\",\"targetRunItemId\":\"" + item + "\"}]}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("Only finished runs are corrected")));

        finish(runId, "1");
        request(runId, "{\"reason\":\"x\",\"lines\":[{\"kind\":\"rename\"}]}").andExpect(status().isBadRequest());
        request(runId, "{\"reason\":\"x\",\"lines\":[{\"kind\":\"add_item\",\"direction\":\"input\",\"itemId\":\"" + material
            + "\",\"qty\":0,\"unit\":\"kg\"}]}").andExpect(status().isBadRequest());

        // Outsiders see nothing; viewers cannot request.
        callAs(OUTSIDER, get("/production-runs/" + runId + "/corrections"), null).andExpect(status().isForbidden());
        ensureMember(VIEWER, "viewer");
        callAs(VIEWER, post("/production-runs/" + runId + "/corrections"),
            "{\"reason\":\"x\",\"lines\":[{\"kind\":\"void_item\",\"targetRunItemId\":\"" + item + "\"}]}")
            .andExpect(status().isForbidden());

        ensureMember(EDITOR, "editor");
        String correctionId = id(callAs(EDITOR, post("/production-runs/" + runId + "/corrections"),
            "{\"reason\":\"Double entry\",\"lines\":[{\"kind\":\"void_item\",\"targetRunItemId\":\"" + item + "\"}]}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.requestedBy").value(EDITOR)), "productionRunCorrectionId");
        // One pending correction per run.
        request(runId, "{\"reason\":\"again\",\"lines\":[{\"kind\":\"set_output_qty\",\"afterQty\":2}]}")
            .andExpect(status().isConflict());
        // Editors request; the owner decides.
        callAs(EDITOR, post("/production-runs/" + runId + "/corrections/" + correctionId + "/approve"), null)
            .andExpect(status().isForbidden());
        call(post("/production-runs/" + runId + "/corrections/" + correctionId + "/reject"), "{\"note\":\"\"}")
            .andExpect(status().isBadRequest());
        call(post("/production-runs/" + runId + "/corrections/" + correctionId + "/reject"), "{\"note\":\"Entry was right\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("rejected"))
            .andExpect(jsonPath("$.data.decisionNote").value("Entry was right"));
        call(post("/production-runs/" + runId + "/corrections/" + correctionId + "/approve")).andExpect(status().isConflict());
        call(get("/production-runs/" + runId + "/items")).andExpect(jsonPath("$.data[0].cancelled").value(false));
    }

    // ---- helpers ----

    private ResultActions request(String runId, String body) throws Exception {
        return call(post("/production-runs/" + runId + "/corrections"), body);
    }

    private void ensureMember(String userId, String role) {
        if (projectMemberRepository.existsByProjectIdAndUserIdAndMemberStatus(DEMO_PROJECT, userId, "active")) {
            return;
        }
        ProjectMember member = new ProjectMember();
        member.setProjectMemberId("pm-corr-" + UUID.randomUUID().toString().substring(0, 8));
        member.setProjectId(DEMO_PROJECT);
        member.setUserId(userId);
        member.setProjectRole(role);
        member.setMemberStatus("active");
        member.setInvitedBy(DEMO_OWNER);
        member.setJoinedAt(OffsetDateTime.now());
        projectMemberRepository.save(member);
    }

    private String item(boolean lotTracked) {
        String id = "itm-corr-" + suffix();
        Item item = new Item();
        item.setItemId(id);
        item.setProjectId(DEMO_PROJECT);
        item.setItemCode(id.toUpperCase());
        item.setItemName(id);
        item.setItemType("material");
        item.setResourceCategory("material");
        item.setUnitId("unit_kg");
        item.setItemStatus("active");
        item.setLotManageYn(lotTracked ? "Y" : "N");
        item.setDeletedYn("N");
        itemRepository.save(item);
        return id;
    }

    private String createLot(String itemId) throws Exception {
        return id(call(post("/lots"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + itemId + "\",\"lotNo\":\"CO-"
            + suffix() + "\"}").andExpect(status().isOk()), "lotId");
    }

    private ResultActions createStock(String itemId, String lotId, String quantity) throws Exception {
        return call(post("/inventories"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + itemId + "\",\"quantity\":"
            + quantity + ",\"location\":\"WH-" + suffix().substring(0, 6) + "\",\"lotId\":\"" + lotId + "\"}");
    }

    private String startRun() throws Exception {
        return id(call(post("/production-runs/start"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowId\":\""
            + DEMO_WORKFLOW + "\",\"plannedOutputQty\":1}").andExpect(status().isOk()), "productionRunId");
    }

    private ResultActions record(String runId, String inventoryId, String itemId, String direction, String qty) throws Exception {
        return call(post("/production-runs/" + runId + "/items"), "{"
            + (inventoryId != null ? "\"inventoryId\":\"" + inventoryId + "\"," : "")
            + "\"itemId\":\"" + itemId + "\",\"direction\":\"" + direction + "\",\"plannedQty\":" + qty
            + ",\"actualQty\":" + qty + ",\"unit\":\"kg\"}").andExpect(status().isOk());
    }

    private void finish(String runId, String outputQty) throws Exception {
        call(post("/production-runs/" + runId + "/finish"), "{\"actualOutputQty\":" + outputQty + "}").andExpect(status().isOk());
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return callAs(DEMO_OWNER, request, null);
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return callAs(DEMO_OWNER, request, body);
    }

    private ResultActions callAs(String userId, MockHttpServletRequestBuilder request, String body) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(userId)));
    }

    private String id(ResultActions result, String field) throws Exception {
        JsonNode data = objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
        return data.path(field).asText();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
