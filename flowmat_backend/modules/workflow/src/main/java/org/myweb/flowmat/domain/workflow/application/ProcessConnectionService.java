package org.myweb.flowmat.domain.workflow.application;

import java.util.List;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessConnectionCreateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessConnectionUpdateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessConnectionResponse;

public interface ProcessConnectionService {

    List<ProcessConnectionResponse> listConnections(String workflowId);

    ProcessConnectionResponse createConnection(ProcessConnectionCreateRequest request);

    ProcessConnectionResponse getConnection(String connectionId);

    ProcessConnectionResponse updateConnection(String connectionId, ProcessConnectionUpdateRequest request);

    void deleteConnection(String connectionId);
}
