package org.myweb.flowmat.domain.workflow.application;

import java.util.List;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessCreateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessPositionRequest;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessUpdateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessResponse;

public interface ProcessService {

    List<ProcessResponse> listProcesses(String workflowId);

    ProcessResponse createProcess(ProcessCreateRequest request);

    ProcessResponse getProcess(String processId);

    ProcessResponse updateProcess(String processId, ProcessUpdateRequest request);

    ProcessResponse updatePosition(String processId, ProcessPositionRequest request);

    void deleteProcess(String processId);
}
