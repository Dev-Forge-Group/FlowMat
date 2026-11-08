package org.myweb.flowmat.domain.user.application;

import org.myweb.flowmat.domain.user.api.dto.response.FaceMatchResponse;
import org.myweb.flowmat.domain.user.api.dto.response.UserTokenResponse;

public interface FaceAuthService {

    void registerDescriptor(String userId, String descriptorJson);

    int getVectorCount(String userId);

    FaceMatchResponse matchFace(String descriptorJson);

    UserTokenResponse selectAccount(String matchToken, String userId,
                                    String deviceId, String userAgent, String ip);

    UserTokenResponse loginByFace(String descriptorJson, String deviceId,
                                  String userAgent, String ip);

    void deleteDescriptor(String userId);
}
