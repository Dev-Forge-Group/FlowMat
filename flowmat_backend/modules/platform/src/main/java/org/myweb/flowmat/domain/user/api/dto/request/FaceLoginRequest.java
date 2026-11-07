package org.myweb.flowmat.domain.user.api.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class FaceLoginRequest {

    @NotEmpty
    private String descriptor;

    @NotEmpty
    @Size(max = 200)
    private String deviceId;
}
