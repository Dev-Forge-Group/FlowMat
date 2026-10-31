package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.domain.catalog.repository.ItemRepository;
import org.myweb.flowmat.domain.quality.application.publicapi.RunInspectionCommand;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Persistence invariants only; rejecting excess precision or preserving it are both acceptable. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class QualityPrecisionPersistenceRegressionIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ItemRepository itemRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RunInspectionCommand runInspectionCommand;

    @Test
    void anAcceptedUpperLimitDecisionMustMatchItsPersistedEvidence() throws Exception {
        checkDecision("1.00004", "standardMax", "1");
    }

    @Test
    void anAcceptedLowerLimitDecisionMustMatchItsPersistedEvidence() throws Exception {
        checkDecision("1", "standardMin", "1.00004");
    }

    @Test
    void anAcceptedMeasurementMustRoundTripWithoutChangingItsRecordedValue() throws Exception {
        String itemId = item();
        MvcResult result = create("/quality-inspections", itemId,
            "\"inspectionType\":\"Precision\",\"measuredValue\":1.00004,\"standardMax\":2");
        if (rejectedWithoutSaving(result, "quality_inspection", itemId)) {
            return;
        }
        JsonNode response = data(result);
        BigDecimal stored = jdbc.queryForObject(
            "select measured_value from quality_inspection where inspection_id = ?", BigDecimal.class,
            response.path("inspectionId").asText());
        assertThat(stored).as("accepted measurement equals its creation response")
            .isEqualByComparingTo(response.path("measuredValue").decimalValue());
        assertThat(list("/quality-inspections", itemId).get(0).path("measuredValue").decimalValue())
            .isEqualByComparingTo(response.path("measuredValue").decimalValue());
    }

    @Test
    void anAcceptedPositiveDefectQuantityMustStayPositiveInStorageAndOnRead() throws Exception {
        String itemId = item();
        MvcResult result = create("/defects", itemId, "\"defectType\":\"Precision\",\"quantity\":0.00001");
        if (rejectedWithoutSaving(result, "defect_log", itemId)) {
            return;
        }
        JsonNode response = data(result);
        BigDecimal stored = jdbc.queryForObject(
            "select quantity from defect_log where defect_log_id = ?", BigDecimal.class,
            response.path("defectLogId").asText());
        assertThat(stored).as("accepted positive quantity stays positive after commit").isPositive();
        assertThat(list("/defects", itemId).get(0).path("quantity").decimalValue()).isPositive();
    }

    @Test
    void exactlyRepresentableMeasurementAndInclusiveLimitAreAccepted() throws Exception {
        String itemId = item();
        MvcResult result = create("/quality-inspections", itemId,
            "\"inspectionType\":\"Precision\",\"measuredValue\":1.0000,\"standardMax\":1.0000");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(list("/quality-inspections", itemId).get(0).path("resultStatus").asText()).isEqualTo("pass");
    }

    @Test
    void theSmallestRepresentablePositiveDefectQuantityIsAccepted() throws Exception {
        String itemId = item();
        MvcResult result = create("/defects", itemId, "\"defectType\":\"Precision\",\"quantity\":0.0001");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(list("/defects", itemId).get(0).path("quantity").decimalValue())
            .isEqualByComparingTo("0.0001");
    }

    @Test
    void anAcceptedStandardLimitMustRoundTripWithoutChangingItsRecordedValue() throws Exception {
        String itemId = item();
        MvcResult result = create("/inspection-standards", itemId,
            "\"inspectionType\":\"Precision\",\"standardMin\":1.00004");
        if (rejectedWithoutSaving(result, "inspection_standard", itemId)) {
            return;
        }
        JsonNode response = data(result);
        BigDecimal stored = jdbc.queryForObject(
            "select standard_min from inspection_standard where standard_id = ?", BigDecimal.class,
            response.path("standardId").asText());
        assertThat(stored).as("accepted standard limit equals its creation response")
            .isEqualByComparingTo(response.path("standardMin").decimalValue());
        assertThat(list("/inspection-standards", itemId).get(0).path("standardMin").decimalValue())
            .isEqualByComparingTo(response.path("standardMin").decimalValue());
    }

    @Test
    void anAcceptedStandardUpdateMustRoundTripWithoutChangingItsRecordedValue() throws Exception {
        String itemId = item();
        MvcResult initial = create("/inspection-standards", itemId,
            "\"inspectionType\":\"Precision\",\"standardMax\":2");
        assertThat(initial.getResponse().getStatus()).isEqualTo(200);
        String standardId = data(initial).path("standardId").asText();
        MvcResult result = mockMvc.perform(put("/inspection-standards/" + standardId)
            .header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"inspectionType\":\"Precision\",\"standardMax\":1.00004}"))
            .andReturn();
        assertThat(result.getResponse().getStatus()).isIn(200, 400);
        BigDecimal stored = jdbc.queryForObject(
            "select standard_max from inspection_standard where standard_id = ?", BigDecimal.class, standardId);
        if (result.getResponse().getStatus() == 400) {
            assertThat(stored).as("rejected update keeps the existing limit").isEqualByComparingTo("2");
            return;
        }
        assertThat(stored).as("accepted standard limit equals its update response")
            .isEqualByComparingTo(data(result).path("standardMax").decimalValue());
    }

    @Test
    void theInternalRunCommandCannotBypassExactStorageValidation() throws Exception {
        String itemId = item();
        MvcResult standard = create("/inspection-standards", itemId,
            "\"inspectionType\":\"Precision\",\"standardMax\":2");
        assertThat(standard.getResponse().getStatus()).isEqualTo(200);
        String standardId = data(standard).path("standardId").asText();
        MvcResult run = mockMvc.perform(post("/production-runs/start")
            .header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowId\":\"" + DEMO_WORKFLOW
                + "\",\"targetItemId\":\"" + itemId + "\",\"plannedOutputQty\":1}"))
            .andReturn();
        assertThat(run.getResponse().getStatus()).isEqualTo(200);
        String runId = data(run).path("productionRunId").asText();
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(jwtProvider.getAuthentication(jwtProvider.generateAccessToken(DEMO_OWNER)));
        SecurityContextHolder.setContext(context);
        try {
            assertThatThrownBy(() -> runInspectionCommand.recordForRun(DEMO_PROJECT, runId, standardId,
                "Precision", new BigDecimal("1.00004"), null, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("measuredValue");
            assertThat(jdbc.queryForObject("select count(*) from quality_inspection where item_id = ?",
                Integer.class, itemId)).isZero();
            assertThat(runInspectionCommand.recordForRun(DEMO_PROJECT, runId, standardId,
                "Precision", new BigDecimal("1.2345"), null, null).result()).isEqualTo("pass");
        } finally {
            SecurityContextHolder.setContext(previous);
        }
        assertThat(list("/quality-inspections", itemId).get(0).path("measuredValue").decimalValue())
            .isEqualByComparingTo("1.2345");
    }

    private void checkDecision(String measurement, String limitField, String limit) throws Exception {
        String itemId = item();
        MvcResult result = create("/quality-inspections", itemId,
            "\"inspectionType\":\"Precision\",\"measuredValue\":" + measurement + ",\"" + limitField + "\":" + limit);
        if (rejectedWithoutSaving(result, "quality_inspection", itemId)) {
            return;
        }
        JsonNode stored = list("/quality-inspections", itemId).get(0);
        int compared = stored.path("measuredValue").decimalValue().compareTo(stored.path(limitField).decimalValue());
        boolean within = limitField.equals("standardMin") ? compared >= 0 : compared <= 0;
        assertThat(stored.path("resultStatus").asText())
            .as("persisted decision agrees with the persisted inclusive limit")
            .isEqualTo(within ? "pass" : "fail");
    }

    private boolean rejectedWithoutSaving(MvcResult result, String table, String itemId) {
        int status = result.getResponse().getStatus();
        assertThat(status).as("precision is either accepted or explicitly rejected").isIn(200, 400);
        if (status != 400) {
            return false;
        }
        String sql = switch (table) {
            case "quality_inspection" -> "select count(*) from quality_inspection where item_id = ?";
            case "inspection_standard" -> "select count(*) from inspection_standard where item_id = ?";
            case "defect_log" -> "select count(*) from defect_log where item_id = ?";
            default -> throw new IllegalArgumentException("Unknown fixture table");
        };
        assertThat(jdbc.queryForObject(sql, Integer.class, itemId)).isZero();
        return true;
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private JsonNode list(String endpoint, String itemId) throws Exception {
        MvcResult result = mockMvc.perform(get(endpoint).param("projectId", DEMO_PROJECT).param("itemId", itemId)
            .header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode rows = data(result);
        assertThat(rows.size()).isEqualTo(1);
        return rows;
    }

    private MvcResult create(String endpoint, String itemId, String fields) throws Exception {
        return mockMvc.perform(post(endpoint)
            .header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + itemId + "\"," + fields + "}"))
            .andReturn();
    }

    private String item() {
        String id = "itm-precision-" + UUID.randomUUID().toString().substring(0, 10);
        Item item = new Item();
        item.setItemId(id);
        item.setProjectId(DEMO_PROJECT);
        item.setItemCode(id);
        item.setItemName(id);
        item.setItemType("material");
        item.setResourceCategory("material");
        item.setUnitId("unit_kg");
        item.setItemStatus("active");
        item.setLotManageYn("N");
        item.setDeletedYn("N");
        itemRepository.saveAndFlush(item);
        return id;
    }
}
