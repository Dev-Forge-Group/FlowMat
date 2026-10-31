package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.domain.catalog.api.ItemDisposalCostController.DisposalCostBody;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ItemDisposalCostPrecisionRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"1e-400", "-1e-400", "0.000100000000000000001", "1.0000000000000000001",
        "9999999999.99999", "1e309", "1e2147483647", "1e-2147483647", "100e2147483647", "1e-2147483648", "1e2147483649"})
    void aCostThatCannotBeStoredExactlyIsRejectedWithoutCostOrHistory(String number) throws Exception {
        String item = item();
        var result = set(item, number, "0").andReturn().getResponse();
        assertThat(result.getStatus()).as("HTTP status for disposalCost %s", number).isEqualTo(400);
        assertThat(result.getContentAsString()).contains("disposalCost");
        assertThat(jdbc.queryForObject("select count(*) from item_disposal_cost where item_id=?", Integer.class, item)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from item_disposal_cost_history where item_id=?", Integer.class, item)).isZero();
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "-0.0, 0", "0e2147483647, 0", "0e-2147483647, 0", "0.0000, 0",
        "1.230000, 1.2300", "9999999999.9999, 9999999999.9999", "1e-4, 0.0001", "123e-2, 1.2300"})
    void acceptedNumericFormsKeepTheirExactCost(String number, String expected) throws Exception {
        String item = item();
        set(item, number, "0").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select disposal_cost from item_disposal_cost where item_id=?", BigDecimal.class, item))
            .isEqualByComparingTo(expected);
        assertThat(jdbc.queryForObject("select version from item_disposal_cost where item_id=?", Long.class, item)).isEqualTo(1L);
        set(item, number, "0").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select version from item_disposal_cost where item_id=?", Long.class, item)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from item_disposal_cost_history where item_id=?", Integer.class, item)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0", "\"0\"", "null", "-1", "9223372036854775808"})
    void expectedVersionRemainsAnIntegralJsonNumber(String version) throws Exception {
        String item = item();
        set(item, "2", version).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from item_disposal_cost where item_id=?", Integer.class, item)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"1.25\"", "true", "{}", "[]"})
    void costRequiresANumberOrExplicitNull(String cost) throws Exception {
        String item = item();
        set(item, cost, "0").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from item_disposal_cost where item_id=?", Integer.class, item)).isZero();
    }

    @ParameterizedTest
    @CsvSource({"UTF-8, application/json", "UTF-16, application/json", "UTF-32BE, application/vnd.flowmat+json"})
    void jsonEncodingsAndCompatibleContentTypesKeepWorking(String encoding, String contentType) throws Exception {
        String item = item();
        mvc.perform(put("/items/" + item + "/disposal-cost").contentType(contentType)
            .content("{\"disposalCost\":1.25,\"expectedVersion\":0}".getBytes(Charset.forName(encoding)))
            .header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select disposal_cost from item_disposal_cost where item_id=?", BigDecimal.class, item))
            .isEqualByComparingTo("1.2500");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"expectedVersion\":0}", "[]", "null"})
    void aMissingCostIsStillRejected(String body) throws Exception {
        String item = item();
        var response = mvc.perform(put("/items/" + item + "/disposal-cost").contentType(MediaType.APPLICATION_JSON)
            .content(body).header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER)))
            .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("disposalCost is required as a number or explicit null.");
        assertThat(jdbc.queryForObject("select count(*) from item_disposal_cost where item_id=?", Integer.class, item)).isZero();
    }

    @Test
    void explicitNullStillClearsTheCostAndKeepsHistory() throws Exception {
        String item = item();
        set(item, "2.5", "0").andExpect(status().isOk());
        set(item, "null", "1").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select disposal_cost from item_disposal_cost where item_id=?", BigDecimal.class, item)).isNull();
        assertThat(jdbc.queryForObject("select version from item_disposal_cost where item_id=?", Long.class, item)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select count(*) from item_disposal_cost_history where item_id=?", Integer.class, item)).isEqualTo(2);
    }

    @Test
    void parserWrapperKeepsTheExistingJsonObjectApiSchema() {
        var converters = ModelConverters.getInstance();
        var previous = converters.resolveAsResolvedSchema(new AnnotatedType(JsonNode.class));
        var current = converters.resolveAsResolvedSchema(new AnnotatedType(DisposalCostBody.class));
        assertThat(current.schema).isEqualTo(previous.schema);
        assertThat(current.referencedSchemas).isEqualTo(previous.referencedSchemas);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0e-2147483647", "0e2147483648"})
    void zeroMantissasRemainValidBeyondBigDecimalScaleBounds(String zero) throws Exception {
        String item = item();
        set(item, zero, "0").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select disposal_cost from item_disposal_cost where item_id=?", BigDecimal.class, item))
            .isEqualByComparingTo("0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e2147483648", "1e-2147483648"})
    void ignoredFieldsKeepTheirPreviousNumberParsing(String ignored) throws Exception {
        String item = item();
        mvc.perform(put("/items/" + item + "/disposal-cost").contentType(MediaType.APPLICATION_JSON)
            .content("{\"disposalCost\":1.25,\"expectedVersion\":0,\"ignored\":{\"number\":" + ignored + "}}")
            .header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select disposal_cost from item_disposal_cost where item_id=?", BigDecimal.class, item))
            .isEqualByComparingTo("1.25");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2", "null", "\"2\""})
    void duplicateCostFieldsKeepTheLastValue(String last) throws Exception {
        String item = item();
        var response = mvc.perform(put("/items/" + item + "/disposal-cost").contentType(MediaType.APPLICATION_JSON)
            .content("{\"disposalCost\":1e-2147483648,\"disposalCost\":" + last + ",\"expectedVersion\":0}")
            .header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER))).andReturn().getResponse();
        if (last.startsWith("\"")) {
            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(jdbc.queryForObject("select count(*) from item_disposal_cost where item_id=?", Integer.class, item)).isZero();
        } else {
            assertThat(response.getStatus()).isEqualTo(200);
            BigDecimal stored = jdbc.queryForObject("select disposal_cost from item_disposal_cost where item_id=?", BigDecimal.class, item);
            if ("null".equals(last)) assertThat(stored).isNull();
            else assertThat(stored).isEqualByComparingTo("2");
        }
    }

    private String item() {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into item(item_id,project_id,item_code,item_name,unit_id,unit_cost) values(?,?,?,?,?,?)",
            id, DEMO_PROJECT, "DP-" + id, "Exact disposal cost", "unit_kg", BigDecimal.ONE);
        return id;
    }

    private ResultActions set(String item, String number, String version) throws Exception {
        return mvc.perform(put("/items/" + item + "/disposal-cost").contentType(MediaType.APPLICATION_JSON)
            .content("{\"disposalCost\":" + number + ",\"expectedVersion\":" + version + "}")
            .header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER)));
    }
}
