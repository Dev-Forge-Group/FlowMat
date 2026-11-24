package org.myweb.flowmat.domain.workflow.application;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessDraftResponse;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * A first process from a description (docs/architecture/adr/ADR-047-process-draft-from-a-description.md): for anyone signed
 * in, a few times an hour each and up to a ceiling a day for the whole server, when the server has drafting set up. The
 * model's answer is checked before it is returned; nothing is saved.
 */
@Service
@RequiredArgsConstructor
public class ProcessDraftService {

    private final ObjectProvider<ProcessDraftModel> model;
    private final ProjectAccess projectAccess;
    private final StringRedisTemplate redis;

    @Value("${app.ai.process-draft.hourly-limit:10}")
    private int hourlyLimit;

    @Value("${app.ai.process-draft.daily-limit:30}")
    private int dailyLimit;

    public ProcessDraftResponse draft(String description) {
        String userId = projectAccess.requireCurrentUserId();
        ProcessDraftModel drafter = model.getIfAvailable();
        if (drafter == null) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Drafting from a description is not set up on this server.");
        }
        String key = "ai:process-draft:" + userId;
        Long used = redis.opsForValue().increment(key);
        if (used != null && used == 1) redis.expire(key, Duration.ofHours(1));
        if (used != null && used > hourlyLimit) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "You have drafted " + hourlyLimit + " times this hour; try again later.");
        }
        String day = "ai:process-draft:day:" + LocalDate.now(ZoneOffset.UTC);
        Long today = redis.opsForValue().increment(day);
        if (today != null && today == 1) redis.expire(day, Duration.ofDays(2));
        if (today != null && today > dailyLimit) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "This server has drafted " + dailyLimit
                + " times today, its limit; start from a sample or try again tomorrow.");
        }
        ProcessDraftResponse draft;
        try {
            draft = drafter.draft(description.trim());
        } catch (ProcessDraftModel.Unavailable e) {
            throw new BusinessException(ErrorCode.BAD_GATEWAY, e.getMessage());
        }
        List<String> problems = ProcessDraftCheck.problems(draft);
        if (!problems.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_GATEWAY, "The draft did not pass its checks (" + problems.get(0)
                + (problems.size() > 1 ? " and " + (problems.size() - 1) + " more" : "") + "); try again or start from a sample.");
        }
        return draft;
    }
}
