package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ItemImportNullRowsRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired JdbcTemplate jdbc;

    @ParameterizedTest
    @CsvSource({"true, true", "true, false", "false, true", "false, false"})
    void nullRowsFailBeforeAnyRowIsApplied(boolean dryRun, boolean onlyNull) throws Exception {
        String code = "NULL-" + UUID.randomUUID();
        String rows = onlyNull ? "[null]" : "[{\"itemCode\":\"" + code + "\",\"itemName\":\"First\"},null,{\"itemCode\":\"" + code + "-last\",\"itemName\":\"Last\"}]";
        var result = mvc.perform(post("/items/import").contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"dryRun\":" + dryRun + ",\"rows\":" + rows + "}")
            .header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER))).andReturn().getResponse();
        assertThat(result.getStatus()).as("null rows: dryRun=%s onlyNull=%s", dryRun, onlyNull).isEqualTo(400);
        assertThat(result.getContentAsString()).contains("rows");
        assertThat(jdbc.queryForObject("select count(*) from item where project_id=? and item_code in (?,?)", Integer.class,
            DEMO_PROJECT, code, code + "-last")).isZero();
    }
}
