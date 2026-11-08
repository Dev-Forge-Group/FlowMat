package org.myweb.flowmat.domain.user.application;

import java.util.List;
import org.myweb.flowmat.domain.user.api.dto.response.AdminUserActionLogResponse;
import org.myweb.flowmat.domain.user.domain.enums.AdminUserActionType;

public interface AdminUserActionLogService {

    void record(String targetUserId, AdminUserActionType actionType, String previousValue, String newValue, String reason);

    List<AdminUserActionLogResponse> listByTargetUserId(String targetUserId);
}
