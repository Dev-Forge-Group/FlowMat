package org.myweb.flowmat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ProjectTimeZoneIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager em;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    private String project() throws Exception {
        var result=mvc.perform(post("/projects").header("Authorization","Bearer "+jwt.generateAccessToken(DEMO_OWNER))
            .contentType(MediaType.APPLICATION_JSON).content("{\"projectName\":\"TZ "+UUID.randomUUID()+"\",\"ownerId\":\""+DEMO_OWNER+"\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.timeZone").value("Asia/Seoul")).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("projectId").asText();
    }
    @Test void aPreviouslyLoadedProjectRenameCannotOverwriteANewerTimeZone() throws Exception {
        String id=project();
        var loaded=new java.util.concurrent.CountDownLatch(1);
        var changed=new java.util.concurrent.CountDownLatch(1);
        var rename=java.util.concurrent.CompletableFuture.runAsync(()->new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(tx->{
            var project=em.find(org.myweb.flowmat.domain.project.domain.entity.Project.class,id);
            loaded.countDown();
            try {if(!changed.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("time zone write did not complete");}
            catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            project.setProjectName("Concurrent rename");em.flush();
        }));
        try {
            assertTrue(loaded.await(10,java.util.concurrent.TimeUnit.SECONDS));
            mvc.perform(put("/projects/"+id+"/time-zone").header("Authorization","Bearer "+jwt.generateAccessToken(DEMO_OWNER)).contentType(MediaType.APPLICATION_JSON).content("{\"timeZone\":\"UTC\",\"expectedVersion\":0}")).andExpect(status().isOk());
        } finally {changed.countDown();}
        rename.get(10,java.util.concurrent.TimeUnit.SECONDS);
        mvc.perform(get("/projects/"+id+"/time-zone").header("Authorization","Bearer "+jwt.generateAccessToken(DEMO_OWNER)))
            .andExpect(jsonPath("$.data.timeZone").value("UTC")).andExpect(jsonPath("$.data.version").value(1));
        mvc.perform(get("/projects/"+id).header("Authorization","Bearer "+jwt.generateAccessToken(DEMO_OWNER)))
            .andExpect(jsonPath("$.data.projectName").value("Concurrent rename"));
    }
    @Test void defaultExistingAndNewProjectsAndOwnerVersionedChange() throws Exception {
        mvc.perform(get("/projects/"+DEMO_PROJECT+"/time-zone").header("Authorization","Bearer "+jwt.generateAccessToken(DEMO_OWNER)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.timeZone").value("Asia/Seoul"));
        String id=project();
        String body="{\"timeZone\":\"America/New_York\",\"expectedVersion\":0}";
        mvc.perform(put("/projects/"+id+"/time-zone").header("Authorization","Bearer "+jwt.generateAccessToken(DEMO_OWNER)).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.timeZone").value("America/New_York")).andExpect(jsonPath("$.data.version").value(1));
        mvc.perform(put("/projects/"+id+"/time-zone").header("Authorization","Bearer "+jwt.generateAccessToken(DEMO_OWNER)).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        mvc.perform(put("/projects/"+id+"/time-zone").header("Authorization","Bearer "+jwt.generateAccessToken(DEMO_OWNER)).contentType(MediaType.APPLICATION_JSON).content("{\"timeZone\":\"Europe/Paris\",\"expectedVersion\":0}"))
            .andExpect(status().isConflict());
        mvc.perform(get("/projects/"+id).header("Authorization","Bearer "+jwt.generateAccessToken(DEMO_OWNER)))
            .andExpect(jsonPath("$.data.timeZone").value("America/New_York"));
    }
    @Test void invalidZoneOrVersionIsRejectedAndOtherProjectMembersCannotWrite() throws Exception {
        String id=project();
        for(String zone:new String[]{"","GMT+09:00","+09:00","not/a-zone"})
            mvc.perform(put("/projects/"+id+"/time-zone").header("Authorization","Bearer "+jwt.generateAccessToken(DEMO_OWNER)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("timeZone",zone,"expectedVersion",0))))
                .andExpect(status().isBadRequest());
        for(String version:new String[]{"null","1.5","-1","\"0\""})
            mvc.perform(put("/projects/"+id+"/time-zone").header("Authorization","Bearer "+jwt.generateAccessToken(DEMO_OWNER)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"timeZone\":\"UTC\",\"expectedVersion\":"+version+"}"))
                .andExpect(status().isBadRequest());
        String viewer="tz-viewer-"+UUID.randomUUID();
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role,member_status) values(?,?,?,'viewer','active')",UUID.randomUUID().toString(),id,viewer);
        mvc.perform(get("/projects/"+id+"/time-zone").header("Authorization","Bearer "+jwt.generateAccessToken(viewer))).andExpect(status().isOk());
        mvc.perform(put("/projects/"+id+"/time-zone").header("Authorization","Bearer "+jwt.generateAccessToken(viewer)).contentType(MediaType.APPLICATION_JSON).content("{\"timeZone\":\"UTC\",\"expectedVersion\":0}"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/projects/"+id+"/time-zone").header("Authorization","Bearer "+jwt.generateAccessToken("tz-outsider"))).andExpect(status().isForbidden());
    }
}
