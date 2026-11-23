package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Calendar replacement and deletion must observe the preceding committed set of shifts. */
@AutoConfigureMockMvc
@Timeout(45)
class EquipmentCalendarConcurrencyIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @ParameterizedTest
    @CsvSource({"false, replace", "false, remove", "true, replace", "true, remove"})
    void aWaitingCalendarWriteReplacesTheEntireCommittedCalendar(boolean initial, String operation) throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 12);
        String project = data(call(post("/projects"), Map.of("projectName", "Calendar race " + tag, "ownerId", DEMO_OWNER)))
            .path("projectId").asText();
        String equipment = data(call(post("/equipments"), Map.of("projectId", project, "equipmentCode", "CAL-" + tag,
            "equipmentName", "Calendar race equipment", "equipmentType", "machine"))).path("equipmentId").asText();
        String path = "/equipments/" + equipment + "/calendar";
        if (initial) {
            call(put(path), shift("06:00", "08:00")).andExpect(status().isOk());
        }
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> waiting = new TransactionTemplate(transactionManager).execute(transaction -> {
                try {
                    call(put(path), shift("10:00", "12:00")).andExpect(status().isOk());
                    Future<Integer> request = pool.submit(() -> call(operation.equals("remove") ? delete(path) : put(path),
                        operation.equals("remove") ? null : shift("15:00", "16:00")).andReturn().getResponse().getStatus());
                    DatabaseContention.awaitWaitingOrDone(jdbc, request, jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                    return request;
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            assertEquals(200, waiting.get(15, TimeUnit.SECONDS));
            JsonNode calendar = data(call(get("/equipments/" + equipment + "/schedule"), null)).path("calendar");
            if (operation.equals("remove")) {
                assertEquals(true, calendar.isNull());
            } else {
                assertEquals(1, calendar.path("shifts").size());
                assertEquals("15:00", calendar.path("shifts").get(0).path("shiftStart").asText());
                assertEquals("16:00", calendar.path("shifts").get(0).path("shiftEnd").asText());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static Map<String, Object> shift(String from, String to) {
        return Map.of("shiftStart", from, "shiftEnd", to, "workDays", List.of(1, 2, 3, 4, 5));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
}