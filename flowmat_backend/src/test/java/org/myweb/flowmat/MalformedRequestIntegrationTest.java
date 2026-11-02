package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A malformed request is the client's mistake, so it gets a 4xx from the global handler instead of a 500 and an ERROR log
 * (2026-11-01 audit B3). UnitController has no local handlers, like most controllers.
 */
@AutoConfigureMockMvc
class MalformedRequestIntegrationTest extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;

    @Test
    void brokenJsonIsABadRequest() throws Exception {
        mvc.perform(post("/units").header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON).content("{\"unitCode\":"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("The request body could not be read."));
    }

    @Test
    void aWronglyTypedParameterIsABadRequest() throws Exception {
        mvc.perform(get("/units").header(HttpHeaders.AUTHORIZATION, bearer()).param("includeInactive", "maybe"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("includeInactive has an invalid value."));
    }

    @Test
    void anUnsupportedMethodIs405WithAllow() throws Exception {
        String allow = mvc.perform(patch("/units").header(HttpHeaders.AUTHORIZATION, bearer()))
            .andExpect(status().isMethodNotAllowed())
            .andReturn().getResponse().getHeader(HttpHeaders.ALLOW);
        assertThat(allow).contains("GET").contains("POST");
    }

    @Test
    void anUnsupportedContentTypeIs415() throws Exception {
        mvc.perform(post("/units").header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.TEXT_PLAIN).content("unit"))
            .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void anUnknownPathIs404() throws Exception {
        mvc.perform(get("/units-that-do-not-exist").header(HttpHeaders.AUTHORIZATION, bearer()))
            .andExpect(status().isNotFound());
    }

    private String bearer() {
        return "Bearer " + jwt.generateAccessToken(DEMO_OWNER);
    }
}
