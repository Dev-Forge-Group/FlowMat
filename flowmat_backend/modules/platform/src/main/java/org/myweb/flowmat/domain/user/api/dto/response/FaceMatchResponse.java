package org.myweb.flowmat.domain.user.api.dto.response;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class FaceMatchResponse {

    private List<FaceMatchedAccount> accounts;
    private String matchToken;

    @Getter
    @Builder
    public static class FaceMatchedAccount {
        private String userId;
        private String maskedEmail;
        private String displayName;
        private int confidence;
    }
}
