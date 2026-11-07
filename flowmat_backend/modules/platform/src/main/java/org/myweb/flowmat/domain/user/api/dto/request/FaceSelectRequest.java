package org.myweb.flowmat.domain.user.api.dto.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class FaceSelectRequest {

    @NotEmpty
    private String matchToken;

    @NotEmpty
    private String userId;

    @NotEmpty
    private String deviceId;
}
