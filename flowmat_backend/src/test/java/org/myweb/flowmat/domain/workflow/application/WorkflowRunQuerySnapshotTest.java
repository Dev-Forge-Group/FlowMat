package org.myweb.flowmat.domain.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.workflow.application.publicapi.ProcessIoRunView;
import org.myweb.flowmat.domain.workflow.application.publicapi.ProcessRunView;
import org.myweb.flowmat.domain.workflow.repository.ProcessIoRepository;
import org.myweb.flowmat.domain.workflow.repository.ProcessRepository;
import org.myweb.flowmat.domain.workflow.repository.WorkflowRepository;

/** A published revision's snapshot entries read as run views (docs/architecture/adr/ADR-018-entity-boundary.md, 2gr and 2gu). */
class WorkflowRunQuerySnapshotTest {
    private static final String SNAPSHOT = "{\"processes\":[{\"processId\":\"p-1\",\"projectId\":\"prj-1\",\"workflowId\":\"wf-1\","
        + "\"processName\":\"Mix\",\"snapshotOnly\":1}],\"processIos\":[{\"processIoId\":\"io-1\",\"processId\":\"p-1\","
        + "\"itemId\":\"item-1\",\"direction\":\"input\",\"schemaJson\":{\"type\":\"number\"}}]}";

    // Unknown snapshot fields are ignored, as by the application's mapper.
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final WorkflowRunQueryImpl query = new WorkflowRunQueryImpl(mock(WorkflowRepository.class), mock(ProcessRepository.class),
        mock(ProcessIoRepository.class), mock(EntityManager.class), json);

    @Test
    void aRevisionsProcessKeepsItsIdsAndItsPropertiesForRules() {
        ProcessRunView process = query.revisionProcess(SNAPSHOT, "p-1").orElseThrow();

        assertThat(process.processId()).isEqualTo("p-1");
        assertThat(process.projectId()).isEqualTo("prj-1");
        assertThat(process.workflowId()).isEqualTo("wf-1");
        assertThat(process.processName()).isEqualTo("Mix");
        assertThat(process.ruleFacts()).containsEntry("processName", "Mix").containsEntry("projectId", "prj-1")
            .doesNotContainKey("snapshotOnly");
    }

    @Test
    void aRevisionsPortKeepsItsSchemaObjectAsText() {
        ProcessIoRunView io = query.revisionProcessIo(SNAPSHOT, "io-1").orElseThrow();

        assertThat(io.processIoId()).isEqualTo("io-1");
        assertThat(io.processId()).isEqualTo("p-1");
        assertThat(io.itemId()).isEqualTo("item-1");
        assertThat(io.direction()).isEqualTo("input");
        assertThat(io.ruleFacts().get("schemaJson")).isEqualTo("{\"type\":\"number\"}");
    }

    @Test
    void anEntryTheRevisionDoesNotHaveIsEmpty() {
        assertThat(query.revisionProcess(SNAPSHOT, "p-2")).isEmpty();
        assertThat(query.revisionProcess(SNAPSHOT, null)).isEmpty();
        assertThat(query.revisionProcessIo(SNAPSHOT, "p-1")).isEmpty();
        assertThat(query.revisionProcess("{}", "p-1")).isEmpty();
    }

    @Test
    void anUnreadableSnapshotOrEntryIsAnInvalidStoredRevision() {
        IllegalStateException snapshot = assertThrows(IllegalStateException.class, () -> query.revisionProcess("{not json", "p-1"));
        IllegalStateException entry = assertThrows(IllegalStateException.class,
            () -> query.revisionProcess("{\"processes\":[{\"processId\":\"p-1\",\"workflowId\":[1,2]}]}", "p-1"));

        assertThat(snapshot.getMessage()).isEqualTo("Stored workflow revision snapshot is invalid.");
        assertThat(entry.getMessage()).isEqualTo("Stored workflow revision process is invalid.");
    }
}
