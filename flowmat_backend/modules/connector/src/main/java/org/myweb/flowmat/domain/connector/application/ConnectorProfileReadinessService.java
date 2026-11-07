package org.myweb.flowmat.domain.connector.application;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.connector.api.dto.response.ConnectorProfileReadinessResponse;
import org.myweb.flowmat.domain.connector.api.dto.response.ConnectorProfileReadinessResponse.Blocker;
import org.myweb.flowmat.domain.connector.repository.ConnectorProfileRepository;
import org.myweb.flowmat.domain.connector.repository.ConnectorProfileVersionRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads deployment configuration without resolving secrets, addresses, connections or execution claims. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConnectorProfileReadinessService {
    private final ConnectorProfileRepository profiles;
    private final ConnectorProfileVersionRepository versions;
    private final ProjectAccessService access;
    private final ConnectorTargetPolicy targets;
    private final ConnectorHttpProperties properties;

    public ConnectorProfileReadinessResponse check(String projectId, String profileVersionId) {
        access.requireProjectOwnerAccess(projectId);
        var version = versions.findById(profileVersionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        var profile = profiles.findById(version.getProfileId())
            .filter(row -> projectId.equals(row.getProjectId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        List<Blocker> blockers = new ArrayList<>();
        if (!properties.isEnabled()) blockers.add(Blocker.CONNECTOR_DISABLED);
        if (!"N".equals(profile.getDeletedYn())) blockers.add(Blocker.PROFILE_RETIRED);
        else if (!profile.isEnabled()) blockers.add(Blocker.PROFILE_DISABLED);
        try {
            // A null alias limits this check to the origin approval and the static HTTP policy.
            targets.validateProfile(projectId, version.getBaseOrigin(), null);
            targets.validatePrivateCidrsConfiguration();
        } catch (BusinessException rejected) {
            blockers.add(Blocker.TARGET_POLICY_REJECTED);
        }
        if (!credentialConfigurationAvailable(projectId, version.getCredentialAlias())) {
            blockers.add(Blocker.CREDENTIAL_CONFIGURATION_UNAVAILABLE);
        }
        return new ConnectorProfileReadinessResponse(profileVersionId, blockers);
    }

    private boolean credentialConfigurationAvailable(String projectId, String alias) {
        try {
            var credential = ConnectorCredentials.registered(properties, projectId, alias);
            if (credential == null) return true;
            return switch (credential.getKind()) {
                case BEARER -> ConnectorCredentials.isValidEnvironmentName(credential.getTokenEnvironment());
                case BASIC -> ConnectorCredentials.isValidEnvironmentName(credential.getUsernameEnvironment())
                    && ConnectorCredentials.isValidEnvironmentName(credential.getPasswordEnvironment());
            };
        } catch (BusinessException unavailable) {
            // Missing, malformed and foreign aliases have the same public result.
            return false;
        }
    }
}
