package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class WorkOrderQuantityValidationIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @ParameterizedTest
    @CsvSource({"create, 0.00001", "update, 0.00001", "create, 0.000049", "update, 0.000049",
        "create, 10000000000", "update, 10000000000", "create, 9999999999.99995", "update, 9999999999.99995"})
    void anUnrepresentableTargetReturns400AndDoesNotChangeTheOrder(String operation, BigDecimal quantity) throws Exception {
        if ("create".equals(operation)) {
            create("Rejected target", quantity).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("targetQuantity")));
        } else {
            String orderId = data(create("Valid target", BigDecimal.ONE)).path("workOrderId").asText();
            call(put("/work-orders/" + orderId), Map.of("workOrderTitle", "Rejected change", "targetQuantity", quantity))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("targetQuantity")));
            assertEquals(0, BigDecimal.ONE.compareTo(targetQuantity(orderId)));
            assertEquals("Valid target", title(orderId));
        }
    }

    @ParameterizedTest
    @CsvSource({"0.00005, 0.0001", "1.23454, 1.2345", "1.23455, 1.2346", "9999999999.9999, 9999999999.9999"})
    void createAndUpdateUseTheDatabaseQuantityPrecision(BigDecimal requested, BigDecimal stored) throws Exception {
        String orderId = data(create("Rounded target", requested)).path("workOrderId").asText();
        assertEquals(0, stored.compareTo(targetQuantity(orderId)));

        call(put("/work-orders/" + orderId), Map.of("workOrderTitle", "Updated target", "targetQuantity", requested))
            .andExpect(status().isOk());

        assertEquals(0, stored.compareTo(targetQuantity(orderId)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"작", "🧱"})
    void titleLimitsMatchDatabaseCharactersOnCreateAndUpdate(String character) throws Exception {
        create(character.repeat(101), BigDecimal.ONE).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("workOrderTitle")));
        String initialTitle = character.repeat(100);
        String orderId = data(create(initialTitle, BigDecimal.ONE)).path("workOrderId").asText();
        assertEquals(100, jdbcTemplate.queryForObject("select char_length(work_order_title) from work_order where work_order_id = ?",
            Integer.class, orderId));

        call(put("/work-orders/" + orderId), Map.of("workOrderTitle", character.repeat(101), "targetQuantity", BigDecimal.ONE))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("workOrderTitle")));
        assertEquals(initialTitle, title(orderId));
        String updatedTitle = character.repeat(99) + "X";
        call(put("/work-orders/" + orderId), Map.of("workOrderTitle", updatedTitle, "targetQuantity", BigDecimal.ONE))
            .andExpect(status().isOk());
        assertEquals(updatedTitle, title(orderId));
    }

    private BigDecimal targetQuantity(String orderId) {
        // Read exact decimals; a JSON double cannot represent the maximum numeric(14,4) boundary exactly.
        return jdbcTemplate.queryForObject("select target_quantity from work_order where work_order_id = ?", BigDecimal.class, orderId);
    }

    private String title(String orderId) {
        return jdbcTemplate.queryForObject("select work_order_title from work_order where work_order_id = ?", String.class, orderId);
    }

    private ResultActions create(String title, BigDecimal quantity) throws Exception {
        return call(post("/work-orders"), Map.of("projectId", DEMO_PROJECT, "workOrderTitle", title, "targetQuantity", quantity));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER))
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }
}
