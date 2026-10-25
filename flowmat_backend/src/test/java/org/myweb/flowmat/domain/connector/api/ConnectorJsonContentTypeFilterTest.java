package org.myweb.flowmat.domain.connector.api;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.logging.RequestCorrelationFilter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ConnectorJsonContentTypeFilterTest {
    private final ConnectorJsonContentTypeFilter filter = new ConnectorJsonContentTypeFilter(new ObjectMapper());

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"application/x-www-form-urlencoded", "multipart/form-data; boundary=x", "text/plain",
        "application/*", "*/*", "broken"})
    void rejectsBeforeLoggingCanParseAnyParameters(String type) throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/projects/p/json-mapping/preview") {
            @Override public String getParameter(String name) { fail("Form parameters must never be parsed"); return null; }
        };
        request.setContextPath("/api");
        request.setContentType(type);
        var response = new MockHttpServletResponse();
        var passed = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> new RequestCorrelationFilter()
            .doFilter(req, res, (endReq, endRes) -> passed.set(true)));
        assertEquals(415, response.getStatus());
        assertFalse(passed.get());
        assertEquals("Content-Type must be application/json.", new ObjectMapper()
            .readTree(response.getContentAsByteArray()).path("message").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "application/json; charset=UTF-8", "APPLICATION/JSON"})
    void allowsJsonWithoutReadingIt(String type) throws Exception {
        var request = new MockHttpServletRequest("POST", "/projects/p/json-mapping/preview");
        request.setContentType(type);
        var passed = new AtomicBoolean();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> passed.set(true));
        assertTrue(passed.get());
    }

    @Test
    void recognizesContextAndMatrixParameters() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/projects/p;v=1/json-mapping/preview");
        request.setContextPath("/api");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("Must reject before downstream filters"));
        assertEquals(415, response.getStatus());
    }

    @Test
    void leavesOtherRoutesAndMethodsAlone() throws Exception {
        for (String[] route : new String[][] {{"POST", "/login"}, {"GET", "/projects/p/json-mapping/preview"}}) {
            var passed = new AtomicBoolean();
            filter.doFilter(new MockHttpServletRequest(route[0], route[1]), new MockHttpServletResponse(),
                (req, res) -> passed.set(true));
            assertTrue(passed.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/projects/p/http-profiles", "/projects/p/http-profiles/pr/status",
        "/processes/p/execution-binding", "/flow-runs/r/steps/s/execute",
        "/flow-runs/r/steps/s/http-executions/e/apply"})
    void protectsEveryNewWriteRouteFromFormParameterParsing(String path) throws Exception {
        var request = new MockHttpServletRequest("POST", path);
        request.setContentType("application/x-www-form-urlencoded");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("Form body must be refused before parameter parsing"));
        assertEquals(415, response.getStatus());
    }

    @Test
    void permitsTheBodylessLocalApplyCommand() throws Exception {
        var request = new MockHttpServletRequest("POST", "/flow-runs/r/steps/s/http-executions/e/apply");
        var passed = new AtomicBoolean();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> passed.set(true));
        assertTrue(passed.get());
    }
}
