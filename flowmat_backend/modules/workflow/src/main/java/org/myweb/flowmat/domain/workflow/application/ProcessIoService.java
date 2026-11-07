package org.myweb.flowmat.domain.workflow.application;

import java.util.List;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessIoCreateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessIoUpdateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessIoResponse;

public interface ProcessIoService {

    List<ProcessIoResponse> listProcessIos(String processId);

    ProcessIoResponse createProcessIo(ProcessIoCreateRequest request);

    ProcessIoResponse getProcessIo(String processIoId);

    ProcessIoResponse updateProcessIo(String processIoId, ProcessIoUpdateRequest request);

    void deleteProcessIo(String processIoId);
}
