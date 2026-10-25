package org.myweb.flowmat.domain.workflow.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorMappingQuery;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorProfileQuery;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessExecutionBindingRequest;
import org.myweb.flowmat.domain.workflow.domain.entity.Process;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessExecutionBinding;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessIo;
import org.myweb.flowmat.domain.workflow.domain.entity.Workflow;
import org.myweb.flowmat.domain.workflow.repository.ProcessExecutionBindingRepository;
import org.myweb.flowmat.domain.workflow.repository.ProcessIoRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
class ProcessExecutionBindingServiceTest {
    @Mock WorkflowAccessService access;
    @Mock ProcessExecutionBindingRepository bindings;
    @Mock ProcessIoRepository ports;
    @Mock ConnectorProfileQuery profiles;
    @Mock ConnectorMappingQuery mappings;
    @Mock EntityManager entities;
    private final ObjectMapper json = new ObjectMapper();
    private ProcessExecutionBindingService service;
    private Process process;
    private Workflow workflow;
    private ProcessIo port;

    @BeforeEach
    void setUp() {
        service = new ProcessExecutionBindingService(access, bindings, ports, profiles, mappings, entities, json);
        process = process("node");
        workflow = new Workflow();
        workflow.setWorkflowId("workflow");
        workflow.setProjectId("project");
        workflow.setDeletedYn("N");
        port = new ProcessIo();
        port.setProcessIoId("out");
        port.setProcessId("node");
        port.setDirection("output");
        port.setDeletedYn("N");
    }

    @Test
    void anUnsetBindingIsReadableAtVersionZeroWithoutResolvingAProfile() {
        when(access.requireProcessReadAccess("node")).thenReturn(process);
        when(access.requireWorkflowReadAccess("workflow")).thenReturn(workflow);
        var response = service.get("node");
        assertEquals("node", response.processId());
        assertEquals(0, response.version());
        assertNull(response.profileVersionId());
        verifyNoInteractions(profiles, mappings, ports, entities);
    }

    @Test
    void aDeletedParentIsRejectedBeforeReadingTheBinding() {
        when(access.requireProcessReadAccess("node")).thenReturn(process);
        when(access.requireWorkflowReadAccess("workflow")).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));
        assertThrows(BusinessException.class, () -> service.get("node"));
        verifyNoInteractions(bindings, profiles);
    }

    @Test
    void aSaveLocksTheWorkflowBeforeRefreshingTheNodeAndValidatesBothMappings() {
        writable();
        selectedPort();
        when(bindings.save(any())).thenAnswer(call -> call.getArgument(0));
        var response = service.set("node", request(0L));
        var order = inOrder(access, entities, bindings, ports, profiles, mappings);
        order.verify(access).requireProcessWriteAccess("node");
        order.verify(access).requireWorkflowWriteAccess("workflow");
        order.verify(entities).refresh(workflow, LockModeType.PESSIMISTIC_WRITE);
        order.verify(entities).refresh(process, LockModeType.PESSIMISTIC_WRITE);
        order.verify(access).requireWorkflowWriteAccess("workflow");
        assertEquals(1, response.version());
        assertEquals("out", response.outputPortId());
        assertEquals(tree("{\"from\":\"\"}"), response.requestMapping());
        verify(profiles).requireOperation("project", "profile-version", "fetch");
        verify(mappings, times(2)).validate(tree("{\"from\":\"\"}"));
    }

    @Test
    void aNodeDeletedWhileWaitingForTheLockCannotBeChanged() {
        writable();
        doNothing().when(entities).refresh(workflow, LockModeType.PESSIMISTIC_WRITE);
        doAnswer(call -> { process.setDeletedYn("Y"); return null; })
            .when(entities).refresh(process, LockModeType.PESSIMISTIC_WRITE);
        assertFailure(ErrorCode.NOT_FOUND, () -> service.set("node", request(0L)));
        verifyNoInteractions(bindings, profiles, mappings);
    }

    @Test
    void workflowAccessIsRecheckedAfterTheLock() {
        writable();
        when(access.requireWorkflowWriteAccess("workflow")).thenReturn(workflow)
            .thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        assertFailure(ErrorCode.FORBIDDEN, () -> service.set("node", request(0L)));
        verifyNoInteractions(bindings, profiles, mappings);
    }

    @ParameterizedTest
    @ValueSource(strings = {"exclusive", "join", "split", "EXCLUSIVE"})
    void gatewaysCannotHaveAnHttpBinding(String nodeType) {
        writable();
        process.setNodeType(nodeType);
        assertFailure(ErrorCode.CONFLICT, () -> service.set("node", request(0L)));
        verifyNoInteractions(profiles, mappings);
    }

    @Test
    void missingExpectedVersionAndPartialClearAreBadRequests() {
        writable();
        assertFailure(ErrorCode.BAD_REQUEST, () -> service.set("node", request(null)));
        assertFailure(ErrorCode.BAD_REQUEST, () -> service.set("node", null));
        assertFailure(ErrorCode.BAD_REQUEST, () -> service.set("node", request(-1L)));
        assertFailure(ErrorCode.BAD_REQUEST, () -> service.set("node",
            new ProcessExecutionBindingRequest(null, "fetch", null, null, null, 0L)));
        verifyNoInteractions(bindings, profiles, mappings);
    }

    @Test
    void missingOrForeignOrInputPortsAreRejected() {
        writable();
        when(ports.findByProcessIoIdAndDeletedYn("out", "N")).thenReturn(Optional.empty());
        assertFailure(ErrorCode.BAD_REQUEST, () -> service.set("node", request(0L)));
        when(ports.findByProcessIoIdAndDeletedYn("out", "N")).thenReturn(Optional.of(port));
        port.setProcessId("another-node");
        assertFailure(ErrorCode.BAD_REQUEST, () -> service.set("node", request(0L)));
        port.setProcessId("node");
        port.setDirection("input");
        assertFailure(ErrorCode.BAD_REQUEST, () -> service.set("node", request(0L)));
        verifyNoInteractions(profiles, mappings);
    }

    @Test
    void unavailableProfileOperationsCannotBeStored() {
        writable();
        selectedPort();
        when(profiles.requireOperation("project", "profile-version", "fetch"))
            .thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        assertFailure(ErrorCode.FORBIDDEN, () -> service.set("node", request(0L)));
        verify(bindings, never()).save(any());
    }

    @Test
    void invalidMappingsAreRefusedBeforeAnySave() {
        writable();
        selectedPort();
        doThrow(new BusinessException(ErrorCode.BAD_REQUEST)).when(mappings).validate(any());
        assertFailure(ErrorCode.BAD_REQUEST, () -> service.set("node", request(0L)));
        verify(bindings, never()).save(any());
    }

    @Test
    void aLostResponseCanReplayOnlyTheIdenticalLastChange() {
        writable();
        selectedPort();
        ProcessExecutionBinding row = binding(1);
        when(bindings.findById("node")).thenReturn(Optional.of(row));
        assertEquals(1, service.set("node", request(0L)).version());
        var different = new ProcessExecutionBindingRequest("profile-version", "fetch", "out",
            tree("{\"literal\":{}}"), tree("{\"from\":\"\"}"), 0L);
        assertFailure(ErrorCode.CONFLICT, () -> service.set("node", different));
        assertFailure(ErrorCode.CONFLICT, () -> service.set("node", request(2L)));
        verify(bindings, never()).save(any());
    }

    @Test
    void clearingRetainsTheVersionAndAnOldSaveCannotResurrectTheBinding() {
        writable();
        ProcessExecutionBinding row = binding(1);
        when(bindings.findById("node")).thenReturn(Optional.of(row));
        when(bindings.save(any())).thenAnswer(call -> call.getArgument(0));
        var clear = new ProcessExecutionBindingRequest(null, null, null, null, null, 1L);
        assertEquals(2, service.set("node", clear).version());
        assertNull(row.getProfileVersionId());
        assertNull(row.getOperationId());
        assertNull(row.getOutputPortId());
        assertNull(row.getRequestMapping());
        assertNull(row.getResponseMapping());
        assertEquals(2, service.set("node", clear).version());
        assertFailure(ErrorCode.CONFLICT, () -> service.set("node", request(0L)));
        verify(bindings).save(row);
        verify(bindings, never()).delete(any());
    }

    @Test
    void aVersionAtItsLimitCannotOverflow() {
        writable();
        when(bindings.findById("node")).thenReturn(Optional.of(binding(Long.MAX_VALUE)));
        assertFailure(ErrorCode.CONFLICT, () -> service.set("node", request(Long.MAX_VALUE)));
        verify(bindings, never()).save(any());
    }

    @Test
    void publicationUsesOneBindingQueryAndFreezesOnlyActiveBindingsInProcessOrder() {
        Process other = process("other");
        Process cleared = process("cleared");
        ProcessExecutionBinding second = binding(8);
        second.setProcessId("other");
        second.setOutputPortId("other-out");
        ProcessIo otherPort = new ProcessIo();
        otherPort.setProcessIoId("other-out");
        otherPort.setProcessId("other");
        otherPort.setDirection("output");
        otherPort.setDeletedYn("N");
        ProcessExecutionBinding empty = new ProcessExecutionBinding();
        empty.setProcessId("cleared");
        when(bindings.findAllByProcessIdIn(List.of("node", "other", "cleared")))
            .thenReturn(List.of(second, empty, binding(3)));
        var frozen = service.snapshotBindings("project", List.of(process, other, cleared), List.of(port, otherPort));
        assertEquals(List.of("node", "other"), frozen.stream().map(row -> row.processId()).toList());
        assertEquals("out", frozen.getFirst().outputPortId());
        verify(profiles, times(2)).requireOperation("project", "profile-version", "fetch");
        verifyNoInteractions(ports, access, entities);
    }

    @Test
    void publicationDoesNotQueryBindingsForAnEmptyWorkflow() {
        assertTrue(service.snapshotBindings("project", List.of(), List.of()).isEmpty());
        verifyNoInteractions(bindings, profiles, mappings);
    }

    @Test
    void savedBindingsKeepTheDecimalScaleOfMappingLiterals() {
        writable();
        selectedPort();
        when(bindings.save(any())).thenAnswer(call -> call.getArgument(0));
        var literal = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
            .put("literal", new java.math.BigDecimal("1.2300"));
        var response = service.set("node", new ProcessExecutionBindingRequest("profile-version", "fetch", "out",
            literal, literal, 0L));
        assertEquals(4, response.requestMapping().path("literal").decimalValue().scale());
        assertEquals(4, response.responseMapping().path("literal").decimalValue().scale());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 0xd800, 0xdc00})
    void mappingsWithUnstorableTextCannotReachTheDatabase(int character) {
        writable();
        selectedPort();
        String invalidText = "private" + (char) character;
        var invalidValue = json.createObjectNode().put("literal", invalidText);
        var invalidKey = json.createObjectNode().set("literal", json.createObjectNode().put(invalidText, true));
        for (JsonNode mapping : List.of(invalidValue, invalidKey)) {
            for (boolean inRequest : List.of(true, false)) {
                var request = new ProcessExecutionBindingRequest("profile-version", "fetch", "out",
                    inRequest ? mapping : tree("{\"from\":\"\"}"), inRequest ? tree("{\"from\":\"\"}") : mapping, 0L);
                BusinessException error = assertThrows(BusinessException.class, () -> service.set("node", request));
                assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCode());
                assertTrue(error.getMessage().contains(inRequest ? "requestMapping" : "responseMapping"));
                assertFalse(error.getMessage().contains("private"));
            }
        }
        verify(bindings, never()).save(any());
    }

    @Test
    void publishedBindingsKeepTheDecimalScaleStoredInTheDraft() {
        ProcessExecutionBinding row = binding(1);
        row.setRequestMapping("{\"literal\":1.2300}");
        row.setResponseMapping("{\"literal\":9007199254740993.123400}");
        when(bindings.findAllByProcessIdIn(List.of("node"))).thenReturn(List.of(row));
        var frozen = service.snapshotBindings("project", List.of(process), List.of(port)).getFirst();
        assertEquals(4, frozen.requestMapping().path("literal").decimalValue().scale());
        assertEquals("9007199254740993.123400", frozen.responseMapping().path("literal").decimalValue().toPlainString());
    }

    @Test
    void publicationRejectsABindingWhoseNodeBecameAGatewayOrSelectedPortWasDeleted() {
        when(bindings.findAllByProcessIdIn(List.of("node"))).thenReturn(List.of(binding(1)));
        process.setNodeType("join");
        assertFailure(ErrorCode.CONFLICT, () -> service.snapshotBindings("project", List.of(process), List.of(port)));
        process.setNodeType("process");
        assertFailure(ErrorCode.CONFLICT, () -> service.snapshotBindings("project", List.of(process), List.of()));
    }

    private void writable() {
        when(access.requireProcessWriteAccess("node")).thenReturn(process);
        when(access.requireWorkflowWriteAccess("workflow")).thenReturn(workflow);
    }

    private void selectedPort() {
        when(ports.findByProcessIoIdAndDeletedYn("out", "N")).thenReturn(Optional.of(port));
    }

    private ProcessExecutionBindingRequest request(Long version) {
        return new ProcessExecutionBindingRequest("profile-version", "fetch", "out",
            tree("{\"from\":\"\"}"), tree("{\"from\":\"\"}"), version);
    }

    private ProcessExecutionBinding binding(long version) {
        ProcessExecutionBinding row = new ProcessExecutionBinding();
        row.setProcessId("node");
        row.setProfileVersionId("profile-version");
        row.setOperationId("fetch");
        row.setOutputPortId("out");
        row.setRequestMapping("{\"from\":\"\"}");
        row.setResponseMapping("{\"from\":\"\"}");
        row.setBindingVersion(version);
        return row;
    }

    private Process process(String id) {
        Process result = new Process();
        result.setProcessId(id);
        result.setProjectId("project");
        result.setWorkflowId("workflow");
        result.setNodeType("process");
        result.setDeletedYn("N");
        return result;
    }

    private JsonNode tree(String value) {
        try { return json.readTree(value); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    private void assertFailure(ErrorCode code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(BusinessException.class, action).getErrorCode());
    }
}
