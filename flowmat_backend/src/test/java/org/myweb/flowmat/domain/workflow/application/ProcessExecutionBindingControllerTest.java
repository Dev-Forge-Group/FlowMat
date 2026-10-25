package org.myweb.flowmat.domain.workflow.application;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.workflow.api.ProcessExecutionBindingController;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

class ProcessExecutionBindingControllerTest {
    @Test
    void accessIsDeniedBeforeObtainingOrReadingTheRequestStream() throws Exception {
        ProcessExecutionBindingService service = mock(ProcessExecutionBindingService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        doThrow(new BusinessException(ErrorCode.FORBIDDEN)).when(service).authorizeWrite("node");
        assertThrows(BusinessException.class, () -> new ProcessExecutionBindingController(service).set("node", request));
        verify(service).authorizeWrite("node");
        verifyNoMoreInteractions(service);
        verifyNoInteractions(request);
    }
}
