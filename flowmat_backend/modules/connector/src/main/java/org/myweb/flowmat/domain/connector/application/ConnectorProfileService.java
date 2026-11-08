package org.myweb.flowmat.domain.connector.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.connector.api.dto.request.ConnectorProfileRequest;
import org.myweb.flowmat.domain.connector.api.dto.request.ConnectorProfileRequest.Operation;
import org.myweb.flowmat.domain.connector.api.dto.response.ConnectorProfileResponse;
import org.myweb.flowmat.domain.connector.api.dto.response.ConnectorProfileVersionResponse;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorProfileOperation;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorProfileQuery;
import org.myweb.flowmat.domain.connector.domain.entity.ConnectorProfile;
import org.myweb.flowmat.domain.connector.domain.entity.ConnectorProfileVersion;
import org.myweb.flowmat.domain.connector.repository.ConnectorProfileRepository;
import org.myweb.flowmat.domain.connector.repository.ConnectorProfileVersionRepository;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owner-managed profiles with append-only configuration versions and one current, immediate kill switch. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConnectorProfileService implements ConnectorProfileQuery {
    private static final TypeReference<List<Operation>> OPERATIONS = new TypeReference<>() { };
    private final ConnectorProfileRepository profiles;
    private final ConnectorProfileVersionRepository versions;
    private final ProjectAccess access;
    private final ConnectorTargetPolicy targets;
    private final IdGenerator ids;
    private final ObjectMapper json;

    public List<ConnectorProfileResponse> list(String projectId) {
        access.requireProjectReadAccess(projectId);
        return profiles.findAllByProjectIdAndDeletedYnOrderByCreatedAtDescProfileIdAsc(projectId, "N").stream()
            .map(this::response).toList();
    }

    public ConnectorProfileResponse get(String projectId, String profileId) {
        access.requireProjectReadAccess(projectId);
        ConnectorProfile profile = active(projectId, profileId);
        return response(profile, current(profile));
    }

    public List<ConnectorProfileVersionResponse> history(String projectId, String profileId) {
        access.requireProjectReadAccess(projectId);
        active(projectId, profileId);
        return versions.findAllByProfileIdOrderByRevisionDesc(profileId).stream().map(version ->
            new ConnectorProfileVersionResponse(version.getProfileVersionId(), version.getProfileId(), version.getRevision(),
                version.getBaseOrigin(), version.getCredentialAlias(), operations(version), version.getCreatedBy(), version.getCreatedAt())).toList();
    }

    /** Historical target metadata remains readable when execution is disabled or the profile was retired. */
    public ConnectorProfileVersionResponse versionMetadata(String projectId, String profileVersionId) {
        access.requireProjectReadAccess(projectId);
        ConnectorProfileVersion version = versions.findById(profileVersionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        ConnectorProfile profile = profiles.findById(version.getProfileId())
            .filter(row -> projectId.equals(row.getProjectId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return new ConnectorProfileVersionResponse(version.getProfileVersionId(), profile.getProfileId(), version.getRevision(),
            version.getBaseOrigin(), version.getCredentialAlias(), operations(version), version.getCreatedBy(), version.getCreatedAt());
    }

    @Transactional
    public ConnectorProfileResponse create(String projectId, ConnectorProfileRequest request) {
        access.requireProjectOwnerAccess(projectId);
        ConnectorProfileRequest checked = requireRequest(request).validated(false);
        String origin = targets.normalizeOrigin(checked.baseOrigin());
        targets.validateProfile(projectId, origin, checked.credentialAlias());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String actor = access.requireCurrentUserId();
        ConnectorProfile profile = new ConnectorProfile();
        profile.setProfileId(ids.generate());
        profile.setProjectId(projectId);
        profile.setName(checked.name());
        profile.setEnabled(checked.enabled());
        profile.setCurrentRevision(1);
        profile.setVersion(0);
        profile.setCreatedBy(actor);
        profile.setUpdatedBy(actor);
        profile.setCreatedAt(now);
        profile.setUpdatedAt(now);
        profiles.saveAndFlush(profile);
        ConnectorProfileVersion version = append(profile, checked, origin, actor, now);
        return response(profile, version);
    }

    @Transactional
    public ConnectorProfileResponse update(String projectId, String profileId, ConnectorProfileRequest request) {
        access.requireProjectOwnerAccess(projectId);
        ConnectorProfileRequest checked = requireRequest(request).validated(true);
        ConnectorProfile profile = lock(projectId, profileId);
        expected(profile, checked.expectedVersion());
        if (profile.getCurrentRevision() == Integer.MAX_VALUE) throw conflict("profile revision has reached its limit.");
        String origin = targets.normalizeOrigin(checked.baseOrigin());
        targets.validateProfile(projectId, origin, checked.credentialAlias());
        profile.setName(checked.name());
        profile.setEnabled(checked.enabled());
        profile.setCurrentRevision(profile.getCurrentRevision() + 1);
        changed(profile);
        ConnectorProfileVersion version = append(profile, checked, origin, profile.getUpdatedBy(), profile.getUpdatedAt());
        profiles.save(profile);
        return response(profile, version);
    }

    @Transactional
    public ConnectorProfileResponse status(String projectId, String profileId, long expectedVersion, boolean enabled) {
        access.requireProjectOwnerAccess(projectId);
        ConnectorProfile profile = lock(projectId, profileId);
        expected(profile, expectedVersion);
        ConnectorProfileVersion version = current(profile);
        // Emergency stopping must remain possible even if deployment approval or credentials have been removed.
        if (enabled) targets.validateProfile(projectId, version.getBaseOrigin(), version.getCredentialAlias());
        profile.setEnabled(enabled);
        changed(profile);
        profiles.save(profile);
        return response(profile, version);
    }

    @Transactional
    public void delete(String projectId, String profileId, long expectedVersion) {
        access.requireProjectOwnerAccess(projectId);
        ConnectorProfile profile = lock(projectId, profileId);
        expected(profile, expectedVersion);
        profile.setEnabled(false);
        profile.setDeletedYn("Y");
        changed(profile);
        profiles.save(profile);
    }

    @Override
    public ConnectorProfileOperation requireOperation(String projectId, String profileVersionId, String operationId) {
        ConnectorProfileVersion version = versions.findById(profileVersionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        ConnectorProfile profile = active(projectId, version.getProfileId());
        if (!profile.isEnabled()) throw conflict("profile is disabled; external execution is not allowed.");
        Operation operation = operations(version).stream().filter(candidate -> candidate.operationId().equals(operationId))
            .findFirst().orElseThrow(() -> bad("operationId is not approved in this profile version."));
        targets.validateProfile(projectId, version.getBaseOrigin(), version.getCredentialAlias());
        return new ConnectorProfileOperation(projectId, profile.getProfileId(), version.getProfileVersionId(), version.getRevision(),
            version.getBaseOrigin(), version.getCredentialAlias(), operation.operationId(), operation.method(), operation.relativePath(),
            operation.successStatuses());
    }

    private ConnectorProfile lock(String projectId, String profileId) {
        ConnectorProfile profile = profiles.lockActive(projectId, profileId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        access.requireProjectOwnerAccess(projectId);
        return profile;
    }

    private ConnectorProfile active(String projectId, String profileId) {
        return profiles.findByProfileIdAndProjectIdAndDeletedYn(profileId, projectId, "N")
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private ConnectorProfileVersion current(ConnectorProfile profile) {
        return requireCurrent(versions.findByProfileIdAndRevision(profile.getProfileId(), profile.getCurrentRevision()).orElse(null));
    }

    private static ConnectorProfileVersion requireCurrent(ConnectorProfileVersion version) {
        if (version == null) throw new IllegalStateException("Connector profile configuration is missing.");
        return version;
    }

    private void expected(ConnectorProfile profile, long expectedVersion) {
        if (expectedVersion < 0) throw bad("expectedVersion is required, a nonnegative integer.");
        if (profile.getVersion() != expectedVersion) throw conflict("expectedVersion changed; reload the profile before saving.");
        if (profile.getVersion() == Long.MAX_VALUE) throw conflict("expectedVersion has reached its limit.");
    }

    private void changed(ConnectorProfile profile) {
        profile.setVersion(profile.getVersion() + 1);
        profile.setUpdatedBy(access.requireCurrentUserId());
        profile.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
    }

    private ConnectorProfileVersion append(ConnectorProfile profile, ConnectorProfileRequest request, String origin,
                                           String actor, OffsetDateTime now) {
        ConnectorProfileVersion version = new ConnectorProfileVersion();
        version.setProfileVersionId(ids.generate());
        version.setProfileId(profile.getProfileId());
        version.setRevision(profile.getCurrentRevision());
        version.setBaseOrigin(origin);
        version.setCredentialAlias(request.credentialAlias());
        try { version.setOperationsJson(json.writeValueAsString(request.operations())); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Connector operations could not be serialized."); }
        version.setCreatedBy(actor);
        version.setCreatedAt(now);
        return versions.saveAndFlush(version);
    }

    private List<Operation> operations(ConnectorProfileVersion version) {
        return operations(version.getOperationsJson());
    }

    private List<Operation> operations(String operationsJson) {
        try { return json.readValue(operationsJson, OPERATIONS); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Connector operations could not be read."); }
    }

    private ConnectorProfileResponse response(ConnectorProfileRepository.CurrentProfile profile) {
        if (profile.getProfileVersionId() == null) throw new IllegalStateException("Connector profile configuration is missing.");
        return new ConnectorProfileResponse(profile.getProfileId(), profile.getProjectId(), profile.getName(), profile.isEnabled(),
            profile.getVersion(), profile.getCurrentRevision(), profile.getProfileVersionId(), profile.getBaseOrigin(),
            profile.getCredentialAlias(), operations(profile.getOperationsJson()), profile.getCreatedAt(), profile.getUpdatedAt());
    }

    private ConnectorProfileResponse response(ConnectorProfile profile, ConnectorProfileVersion version) {
        return new ConnectorProfileResponse(profile.getProfileId(), profile.getProjectId(), profile.getName(), profile.isEnabled(),
            profile.getVersion(), profile.getCurrentRevision(), version.getProfileVersionId(), version.getBaseOrigin(),
            version.getCredentialAlias(), operations(version), profile.getCreatedAt(), profile.getUpdatedAt());
    }

    private static ConnectorProfileRequest requireRequest(ConnectorProfileRequest request) {
        if (request == null) throw bad("body is required.");
        return request;
    }

    private static BusinessException bad(String message) { return new BusinessException(ErrorCode.BAD_REQUEST, message); }
    private static BusinessException conflict(String message) { return new BusinessException(ErrorCode.CONFLICT, message); }
}
