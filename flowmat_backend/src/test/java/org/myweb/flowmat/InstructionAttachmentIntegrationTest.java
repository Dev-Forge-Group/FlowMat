package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class InstructionAttachmentIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    static final Path ROOT = root();
    static Path root() { try { return Files.createTempDirectory("flowmat-attachments-"); } catch (Exception e) { throw new IllegalStateException(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("app.storage.upload-dir", () -> ROOT.toString());
        r.add("app.storage.max-file-size-bytes", () -> 1024);
    }
    @Test void uploadedFilesDownloadWithSafeHeadersAndSameRequestReplays() throws Exception {
        String wi = draft(), id = UUID.randomUUID().toString();
        long initial; try(var files=Files.walk(ROOT)) { initial=files.filter(Files::isRegularFile).count(); }
        upload(wi,id,"Guide.txt","Use the guard.").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.fileName").value("Guide.txt"))
            .andExpect(jsonPath("$.data.sizeBytes").value(14))
            .andExpect(jsonPath("$.data.attachmentId").value(id));
        upload(wi,id,"Guide.txt","Use the guard.").andExpect(status().isOk());
        call(get(base(wi))).andExpect(jsonPath("$.data.length()").value(1));
        call(get(base(wi)+"/"+id+"/download")).andExpect(status().isOk())
            .andExpect(header().string("X-Content-Type-Options","nosniff"))
            .andExpect(header().string("Cache-Control","private, no-store"))
            .andExpect(content().bytes("Use the guard.".getBytes(StandardCharsets.UTF_8)));
        upload(wi,id,"Guide.txt","Different").andExpect(status().isConflict());
        try (var files=Files.walk(ROOT)) { assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(initial+1); }
        call(delete(base(wi)+"/"+id)).andExpect(status().isOk());
        call(delete(base(wi)+"/"+id)).andExpect(status().isOk());
        call(get(base(wi)+"/"+id+"/download")).andExpect(status().isNotFound());
        upload(wi,id,"Guide.txt","Use the guard.").andExpect(status().isConflict());
    }
    @Test void revisionsShareImmutableFileReferencesAndDraftDeletionKeepsReleasedDownload() throws Exception {
        String wi=draft(), id=UUID.randomUUID().toString();
        upload(wi,id,"Guide.txt","Keep for history").andExpect(status().isOk());
        call(post("/work-instructions/"+wi+"/steps").contentType("application/json").content("{\"text\":\"Check guard\"}")).andExpect(status().isOk());
        call(post("/work-instructions/"+wi+"/release")).andExpect(status().isOk());
        call(delete(base(wi)+"/"+id)).andExpect(status().isConflict());
        // A lost upload reply may be recovered after release; a new upload cannot alter the released instruction.
        upload(wi,id,"Guide.txt","Keep for history").andExpect(status().isOk());
        upload(wi,UUID.randomUUID().toString(),"More.txt","New").andExpect(status().isConflict());
        String copy=mapper.readTree(call(post("/work-instructions/"+wi+"/revise")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("instructionId").asText();
        var rows=mapper.readTree(call(get(base(copy))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(rows.size()).isEqualTo(1);
        String copied=rows.get(0).path("attachmentId").asText();
        assertThat(copied).isNotEqualTo(id);
        call(delete(base(copy)+"/"+copied)).andExpect(status().isOk());
        call(get(base(wi)+"/"+id+"/download")).andExpect(content().string("Keep for history"));
        call(post("/work-instructions/"+copy+"/release")).andExpect(status().isOk());
        call(get(base(wi)+"/"+id+"/download")).andExpect(status().isOk()); // retired
    }
    @Test void membershipAndInstructionOwnershipAreCheckedBeforeAnyStorageWrite() throws Exception {
        String wi=draft(), id=UUID.randomUUID().toString();
        uploadAs("attachment-outsider",wi,id,"Guide.txt","Secret").andExpect(status().isForbidden());
        upload(wi,id,"Guide.txt","Secret").andExpect(status().isOk());
        mvc.perform(get(base(wi)).header("Authorization","Bearer "+jwt.generateAccessToken("attachment-outsider"))).andExpect(status().isForbidden());
        mvc.perform(get(base(wi)+"/"+id+"/download").header("Authorization","Bearer "+jwt.generateAccessToken("attachment-outsider"))).andExpect(status().isForbidden());
        String viewer="att-viewer-"+UUID.randomUUID();
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role,member_status) values(?,?,?,'viewer','active')",UUID.randomUUID().toString(),DEMO_PROJECT,viewer);
        mvc.perform(get(base(wi)+"/"+id+"/download").header("Authorization","Bearer "+jwt.generateAccessToken(viewer))).andExpect(status().isOk());
        uploadAs(viewer,wi,UUID.randomUUID().toString(),"Guide.txt","Secret").andExpect(status().isForbidden());
        mvc.perform(delete(base(wi)+"/"+id).header("Authorization","Bearer "+jwt.generateAccessToken(viewer))).andExpect(status().isForbidden());
        call(get(base(draft())+"/"+id+"/download")).andExpect(status().isNotFound());
    }
    @Test void invalidContentOversizeAndIdsAreRejected() throws Exception {
        String wi=draft();
        upload(wi,"not-a-uuid","Guide.txt","Text").andExpect(status().isBadRequest());
        upload(wi,UUID.randomUUID().toString(),"Huge.txt","x".repeat(1025)).andExpect(status().isBadRequest());
        mvc.perform(multipart(base(wi)+"/"+UUID.randomUUID()).file(new MockMultipartFile("file","fake.png","image/png","<script>x</script>".getBytes(StandardCharsets.UTF_8))).with(r->{r.setMethod("PUT");return r;}).header("Authorization","Bearer "+jwt.generateAccessToken(DEMO_OWNER))).andExpect(status().isBadRequest());
        upload(wi,UUID.randomUUID().toString(),"empty.txt","").andExpect(status().isBadRequest());
        call(get(base(wi))).andExpect(jsonPath("$.data.length()").value(0));
    }
    @Test void missingMultipartFileIsAValidationError() throws Exception {
        String wi=draft();
        mvc.perform(multipart(base(wi)+"/"+UUID.randomUUID()).with(r->{r.setMethod("PUT");return r;}).header("Authorization","Bearer "+jwt.generateAccessToken(DEMO_OWNER))).andExpect(status().isBadRequest());
    }
    private String draft() throws Exception {
        String item=mapper.readTree(call(post("/items").contentType("application/json").content("{\"projectId\":\""+DEMO_PROJECT+"\",\"itemCode\":\"ATT-"+UUID.randomUUID()+"\",\"itemName\":\"Guard\",\"unitId\":\"unit_ea\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("itemId").asText();
        return mapper.readTree(call(post("/work-instructions").contentType("application/json").content("{\"projectId\":\""+DEMO_PROJECT+"\",\"itemId\":\""+item+"\",\"title\":\"Guard setup\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("instructionId").asText();
    }
    private String base(String wi) { return "/work-instructions/"+wi+"/attachments"; }
    private ResultActions upload(String wi,String id,String name,String body) throws Exception { return uploadAs(DEMO_OWNER,wi,id,name,body); }
    private ResultActions uploadAs(String actor,String wi,String id,String name,String body) throws Exception {
        return mvc.perform(multipart(base(wi)+"/"+id).file(new MockMultipartFile("file",name,"text/plain",body.getBytes(StandardCharsets.UTF_8))).with(r->{r.setMethod("PUT");return r;}).header("Authorization","Bearer "+jwt.generateAccessToken(actor)));
    }
    private ResultActions call(MockHttpServletRequestBuilder b) throws Exception { return mvc.perform(b.header("Authorization","Bearer "+jwt.generateAccessToken(DEMO_OWNER))); }
}
