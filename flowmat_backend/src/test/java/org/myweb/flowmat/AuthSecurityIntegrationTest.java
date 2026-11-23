package org.myweb.flowmat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class AuthSecurityIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void csrfEndpointIssuesReadableTokenCookie() throws Exception {
        mockMvc.perform(get("/auth/csrf"))
            .andExpect(status().isOk())
            .andExpect(cookie().exists("XSRF-TOKEN"));
    }

    @Test
    void refreshRejectsMissingCsrfTokenBeforeAuthentication() throws Exception {
        mockMvc.perform(post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void logoutAcceptsCsrfTokenAndThenRejectsMissingRefreshCredential() throws Exception {
        MvcResult csrf = mockMvc.perform(get("/auth/csrf"))
            .andExpect(status().isOk())
            .andReturn();
        Cookie csrfCookie = csrf.getResponse().getCookie("XSRF-TOKEN");

        mockMvc.perform(post("/auth/logout")
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnauthorized());
    }
}
