package org.myweb.flowmat.domain.user.api;

import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answers every face login path with 404 while face login is off, the default (ADR-030). Nothing is matched or
 * collected; face data stored earlier stays as it is.
 */
@RestController
@ConditionalOnProperty(prefix = "app.auth.face-login", name = "enabled", havingValue = "false", matchIfMissing = true)
@RequestMapping("/auth/face")
public class FaceLoginDisabledController {

    @RequestMapping("/**")
    public void disabled() {
        throw new BusinessException(ErrorCode.FACE_LOGIN_DISABLED);
    }
}
