package org.myweb.flowmat.domain.workflow.annotation.application;

import org.myweb.flowmat.domain.workflow.annotation.domain.CanvasAnnotation;
import org.springframework.stereotype.Service;

@Service
public class CanvasAnnotationReconcileService {

    public boolean shouldDiscardIncoming(CanvasAnnotation current, Integer nextVersion, Long nextVersionNonce) {
        if (current == null || nextVersion == null || nextVersionNonce == null) {
            return false;
        }
        if (current.getVersion() > nextVersion) {
            return true;
        }
        return current.getVersion() == nextVersion && current.getVersionNonce() >= nextVersionNonce;
    }
}
