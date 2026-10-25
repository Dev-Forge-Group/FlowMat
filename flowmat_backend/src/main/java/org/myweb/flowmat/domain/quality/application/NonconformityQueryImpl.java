package org.myweb.flowmat.domain.quality.application;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.quality.application.publicapi.NcrCheckView;
import org.myweb.flowmat.domain.quality.application.publicapi.NcrView;
import org.myweb.flowmat.domain.quality.application.publicapi.NonconformityQuery;
import org.myweb.flowmat.domain.quality.repository.NonconformityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NonconformityQueryImpl implements NonconformityQuery {

    private final NonconformityRepository nonconformityRepository;

    @Override
    public Optional<NcrView> findProjectNcr(String projectId, String nonconformityId) {
        return nonconformityRepository.findById(nonconformityId)
            .filter(ncr -> ncr.getProjectId().equals(projectId))
            .map(ncr -> new NcrView(ncr.getNonconformityId(), ncr.getProjectId(), ncr.getNcrNo(), ncr.getStatus(), ncr.getDisposition(),
                ncr.getItemId(), ncr.getLotId()));
    }

    @Override
    public List<NcrCheckView> findPendingChecks(Collection<String> projectIds) {
        if (projectIds.isEmpty()) {
            return List.of();
        }
        return nonconformityRepository
            .findAllByProjectIdInAndStatusAndVerifiedAtIsNullAndVerificationDueDateIsNotNull(projectIds, "closed").stream()
            .map(ncr -> new NcrCheckView(ncr.getNonconformityId(), ncr.getProjectId(), ncr.getNcrNo(), ncr.getTitle(),
                ncr.getVerificationDueDate()))
            .toList();
    }
}
