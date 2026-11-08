package org.myweb.flowmat.domain.quality.application;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.quality.api.dto.response.NonconformityDefectLinkResponse;
import org.myweb.flowmat.domain.quality.domain.entity.Nonconformity;
import org.myweb.flowmat.domain.quality.repository.NonconformityDefectRepository;
import org.myweb.flowmat.domain.quality.repository.NonconformityRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which nonconformity holds each of a project's defects (docs/domain/nonconformity.md), so the defect list of a run or a
 * LOT can show the NCR number and offer only the defects that are free to gather. Cancelling a nonconformity lets its
 * defects go (N9), so only open and closed ones appear.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NonconformityDefectLinkService {

    private final NonconformityRepository nonconformityRepository;
    private final NonconformityDefectRepository nonconformityDefectRepository;
    private final ProjectAccessService projectAccessService;

    public List<NonconformityDefectLinkResponse> links(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "projectId is required.");
        }
        projectAccessService.requireProjectReadAccess(projectId);
        Map<String, Nonconformity> nonconformities = nonconformityRepository.findAllByProjectIdOrderByRaisedAtDesc(projectId)
            .stream()
            .collect(Collectors.toMap(Nonconformity::getNonconformityId, Function.identity()));
        if (nonconformities.isEmpty()) {
            return List.of();
        }
        return nonconformityDefectRepository.findAllByNonconformityIdIn(nonconformities.keySet()).stream()
            .map(link -> {
                Nonconformity nonconformity = nonconformities.get(link.getNonconformityId());
                return new NonconformityDefectLinkResponse(link.getDefectLogId(), nonconformity.getNonconformityId(),
                    nonconformity.getNcrNo(), nonconformity.getStatus());
            })
            .sorted(Comparator.comparing(NonconformityDefectLinkResponse::defectLogId))
            .toList();
    }
}
