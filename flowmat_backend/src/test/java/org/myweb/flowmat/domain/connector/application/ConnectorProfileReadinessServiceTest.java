package org.myweb.flowmat.domain.connector.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.domain.connector.api.dto.response.ConnectorProfileReadinessResponse;
import org.myweb.flowmat.domain.connector.api.dto.response.ConnectorProfileReadinessResponse.Blocker;
import org.myweb.flowmat.domain.connector.domain.entity.ConnectorProfile;
import org.myweb.flowmat.domain.connector.domain.entity.ConnectorProfileVersion;
import org.myweb.flowmat.domain.connector.repository.ConnectorProfileRepository;
import org.myweb.flowmat.domain.connector.repository.ConnectorProfileVersionRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.dao.DataAccessResourceFailureException;

class ConnectorProfileReadinessServiceTest {
    private final ConnectorProfileRepository profiles = mock(ConnectorProfileRepository.class);
    private final ConnectorProfileVersionRepository versions = mock(ConnectorProfileVersionRepository.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final ConnectorTargetPolicy targets = mock(ConnectorTargetPolicy.class);
    private final ConnectorHttpProperties properties = spy(new ConnectorHttpProperties());
    private ConnectorProfileReadinessService service;
    private ConnectorProfile profile;
    private ConnectorProfileVersion version;

    @BeforeEach
    void setup() {
        properties.setEnabled(true);
        properties.setCredentials(Map.of());
        clearInvocations(properties);
        profile = new ConnectorProfile();
        profile.setProfileId("profile"); profile.setProjectId("project");
        profile.setEnabled(true); profile.setCurrentRevision(9);
        version = new ConnectorProfileVersion();
        version.setProfileVersionId("pinned-version"); version.setProfileId("profile");
        version.setRevision(2); version.setBaseOrigin("https://approved.example.test");
        when(versions.findById("pinned-version")).thenReturn(Optional.of(version));
        when(profiles.findById("profile")).thenReturn(Optional.of(profile));
        service = new ConnectorProfileReadinessService(profiles, versions, access, targets, properties);
    }

    @AfterEach
    void neverLocksOrChangesStoredProfiles() {
        verify(profiles, never()).lockActive(anyString(), anyString());
        verify(profiles, never()).save(any());
        verify(profiles, never()).saveAndFlush(any());
        verify(profiles, never()).flush();
        verify(versions, never()).save(any());
        verify(versions, never()).saveAndFlush(any());
        verify(versions, never()).flush();
    }

    @Test
    void anonymousConfigurationIsReadyWithoutCredentialsOrAnyEnvironmentDependency() {
        var result = service.check("project", "pinned-version");

        assertEquals("pinned-version", result.profileVersionId());
        assertTrue(result.configurationReady());
        assertEquals(List.of(), result.blockers());
        assertFalse(result.credentialValuesChecked());
        assertFalse(result.connectionTested());
        verify(targets).validateProfile("project", "https://approved.example.test", null);
    }

    @Test
    void ownerAuthorizationComesBeforeAnyVersionOrDeploymentRead() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN)).when(access).requireProjectOwnerAccess("project");

        assertCode(ErrorCode.FORBIDDEN);

        verifyNoInteractions(profiles, versions, targets, properties);
    }

    @Test
    void unknownVersionIsNotFoundBeforeConsultingTheDeploymentConfiguration() {
        when(versions.findById("pinned-version")).thenReturn(Optional.empty());
        assertCode(ErrorCode.NOT_FOUND);
        verifyNoInteractions(profiles, targets, properties);
    }

    @Test
    void missingParentIsNotFoundBeforeConsultingTheDeploymentConfiguration() {
        when(profiles.findById("profile")).thenReturn(Optional.empty());
        assertCode(ErrorCode.NOT_FOUND);
        verifyNoInteractions(targets, properties);
    }

    @Test
    void anotherProjectsVersionIsNotFoundEvenWhenTheConnectorIsDisabled() {
        profile.setProjectId("private-other-project");
        assertCode(ErrorCode.NOT_FOUND);
        verifyNoInteractions(targets, properties);
    }

    @Test
    void versionAndParentAreReadOnlyAfterOwnerAccessIsGranted() {
        service.check("project", "pinned-version");
        var order = inOrder(access, versions, profiles);
        order.verify(access).requireProjectOwnerAccess("project");
        order.verify(versions).findById("pinned-version");
        order.verify(profiles).findById("profile");
    }

    @Test
    void globalDisabledIsAnExplicitBlockerWithoutImplyingCredentialsWereRead() {
        properties.setEnabled(false);
        assertBlockers(Blocker.CONNECTOR_DISABLED);
    }

    @Test
    void disabledProfileHasItsOwnBlocker() {
        profile.setEnabled(false);
        assertBlockers(Blocker.PROFILE_DISABLED);
    }

    @Test
    void retiredProfileDoesNotDuplicateTheDisabledReason() {
        profile.setDeletedYn("Y"); profile.setEnabled(false);
        assertBlockers(Blocker.PROFILE_RETIRED);
    }

    @Test
    void rejectedOriginProducesOnlyAStaticPolicyCode() {
        doThrow(new BusinessException(ErrorCode.BAD_REQUEST, "private-origin-or-configuration-details"))
            .when(targets).validateProfile(anyString(), anyString(), isNull());
        var result = service.check("project", "pinned-version");
        assertEquals(List.of(Blocker.TARGET_POLICY_REJECTED), result.blockers());
        assertFalse(result.toString().contains("private-origin-or-configuration-details"));
    }

    @Test
    void validBearerKeysDoNotNeedConfiguredSecretValuesForStaticReadiness() {
        register(ConnectorHttpProperties.Kind.BEARER, "FLOWMAT_STATIC_ONLY_TOKEN", null, null);
        assertTrue(service.check("project", "pinned-version").configurationReady());
    }

    @Test
    void validBasicKeysDoNotNeedConfiguredSecretValuesForStaticReadiness() {
        register(ConnectorHttpProperties.Kind.BASIC, null, "FLOWMAT_STATIC_ONLY_USER", "FLOWMAT_STATIC_ONLY_PASSWORD");
        assertTrue(service.check("project", "pinned-version").configurationReady());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "lower_case", "WITH SPACE", "WITH.DOT", "${ENV_TOKEN}", "ENV_TOKEN\n"})
    void malformedBearerEnvironmentNamesHaveOneSafeCredentialBlocker(String name) {
        register(ConnectorHttpProperties.Kind.BEARER, name, null, null);
        assertBlockers(Blocker.CREDENTIAL_CONFIGURATION_UNAVAILABLE);
    }

    @Test
    void missingOrTooLongBearerEnvironmentNameIsRejected() {
        register(ConnectorHttpProperties.Kind.BEARER, null, null, null);
        assertBlockers(Blocker.CREDENTIAL_CONFIGURATION_UNAVAILABLE);
        register(ConnectorHttpProperties.Kind.BEARER, "X".repeat(129), null, null);
        assertBlockers(Blocker.CREDENTIAL_CONFIGURATION_UNAVAILABLE);
    }

    @Test
    void basicRequiresBothValidEnvironmentNames() {
        register(ConnectorHttpProperties.Kind.BASIC, null, null, "VALID_PASSWORD");
        assertBlockers(Blocker.CREDENTIAL_CONFIGURATION_UNAVAILABLE);
        register(ConnectorHttpProperties.Kind.BASIC, null, "VALID_USER", null);
        assertBlockers(Blocker.CREDENTIAL_CONFIGURATION_UNAVAILABLE);
        register(ConnectorHttpProperties.Kind.BASIC, null, "invalid-user", "VALID_PASSWORD");
        assertBlockers(Blocker.CREDENTIAL_CONFIGURATION_UNAVAILABLE);
        register(ConnectorHttpProperties.Kind.BASIC, null, "VALID_USER", "${PRIVATE_PASSWORD}");
        assertBlockers(Blocker.CREDENTIAL_CONFIGURATION_UNAVAILABLE);
    }

    @Test
    void unknownAliasForeignAliasAndMissingKindAreIndistinguishable() {
        version.setCredentialAlias("registered");
        assertBlockers(Blocker.CREDENTIAL_CONFIGURATION_UNAVAILABLE);
        register(ConnectorHttpProperties.Kind.BEARER, "OTHER_PROJECT_TOKEN", null, null);
        properties.getCredentials().get("registered").setProjectId("other-project");
        assertBlockers(Blocker.CREDENTIAL_CONFIGURATION_UNAVAILABLE);
        register(null, "PRIVATE_TOKEN", null, null);
        assertBlockers(Blocker.CREDENTIAL_CONFIGURATION_UNAVAILABLE);
        properties.setCredentials(null);
        assertBlockers(Blocker.CREDENTIAL_CONFIGURATION_UNAVAILABLE);
    }

    @Test
    void invalidAliasSyntaxHasTheSameSafeCredentialBlocker() {
        version.setCredentialAlias("${OTHER_PROJECT_TOKEN}");
        assertBlockers(Blocker.CREDENTIAL_CONFIGURATION_UNAVAILABLE);
    }

    @Test
    void independentBlockersHaveAStableOrderAndDoNotExposeConfiguration() throws Exception {
        properties.setEnabled(false); profile.setDeletedYn("Y"); profile.setEnabled(false);
        version.setCredentialAlias("private-alias");
        doThrow(new BusinessException(ErrorCode.BAD_REQUEST, "private-policy-message"))
            .when(targets).validateProfile(anyString(), anyString(), isNull());

        var result = service.check("project", "pinned-version");

        assertEquals(List.of(Blocker.CONNECTOR_DISABLED, Blocker.PROFILE_RETIRED,
            Blocker.TARGET_POLICY_REJECTED, Blocker.CREDENTIAL_CONFIGURATION_UNAVAILABLE), result.blockers());
        var body = new ObjectMapper().valueToTree(result);
        assertEquals(Set.of("profileVersionId", "configurationReady", "blockers", "credentialValuesChecked", "connectionTested"),
            body.propertyStream().map(Map.Entry::getKey).collect(Collectors.toSet()));
        String wire = body.toString();
        assertFalse(wire.contains("private-alias"));
        assertFalse(wire.contains("private-policy-message"));
        assertFalse(wire.contains("approved.example"));
    }

    @Test
    void thePinnedVersionIsCheckedWithoutLookingUpTheCurrentRevision() {
        register(ConnectorHttpProperties.Kind.BEARER, "PINNED_TOKEN_ENV", null, null);
        version.setBaseOrigin("https://old.example.test");
        assertTrue(service.check("project", "pinned-version").configurationReady());
        verify(targets).validateProfile("project", "https://old.example.test", null);
        verify(versions, never()).findByProfileIdAndRevision(anyString(), anyInt());
        verify(versions, never()).findCurrentVersions(anyString());
    }

    @Test
    void versionDatabaseFailuresAreNotMisreportedAsConfigurationBlockers() {
        var failure = new DataAccessResourceFailureException("database unavailable");
        when(versions.findById("pinned-version")).thenThrow(failure);
        assertSame(failure, assertThrows(RuntimeException.class, () -> service.check("project", "pinned-version")));
        verifyNoInteractions(profiles, targets, properties);
    }

    @Test
    void parentDatabaseFailuresAreNotMisreportedAsConfigurationBlockers() {
        var failure = new DataAccessResourceFailureException("database unavailable");
        when(profiles.findById("profile")).thenThrow(failure);
        assertSame(failure, assertThrows(RuntimeException.class, () -> service.check("project", "pinned-version")));
        verifyNoInteractions(targets, properties);
    }

    @Test
    void responseCopiesItsBlockersAndDerivesItsBooleanFlags() {
        var reasons = new ArrayList<>(List.of(Blocker.CONNECTOR_DISABLED));
        var blocked = new ConnectorProfileReadinessResponse("pv", true, reasons, true, true);
        reasons.clear();
        assertEquals(List.of(Blocker.CONNECTOR_DISABLED), blocked.blockers());
        assertFalse(blocked.configurationReady());
        assertFalse(blocked.credentialValuesChecked());
        assertFalse(blocked.connectionTested());
        assertThrows(UnsupportedOperationException.class, () -> blocked.blockers().clear());
        var ready = new ConnectorProfileReadinessResponse("pv", false, List.of(), true, true);
        assertTrue(ready.configurationReady());
        assertFalse(ready.credentialValuesChecked());
        assertFalse(ready.connectionTested());
    }

    @Test
    void environmentNameBoundsMatchExecutionValidationWithoutLookingUpAnyValue() {
        assertTrue(ConnectorCredentials.isValidEnvironmentName("A"));
        assertTrue(ConnectorCredentials.isValidEnvironmentName("A" + "_".repeat(127)));
        assertFalse(ConnectorCredentials.isValidEnvironmentName("A" + "_".repeat(128)));
        assertFalse(ConnectorCredentials.isValidEnvironmentName(null));
        assertFalse(ConnectorCredentials.isValidEnvironmentName("1_TOKEN"));
    }

    private void register(ConnectorHttpProperties.Kind kind, String token, String username, String password) {
        var credential = new ConnectorHttpProperties.Credential();
        credential.setProjectId("project"); credential.setKind(kind);
        credential.setTokenEnvironment(token); credential.setUsernameEnvironment(username);
        credential.setPasswordEnvironment(password);
        properties.setCredentials(Map.of("registered", credential));
        version.setCredentialAlias("registered");
    }

    private void assertBlockers(Blocker... expected) {
        var result = service.check("project", "pinned-version");
        assertEquals(List.of(expected), result.blockers());
        assertFalse(result.configurationReady());
        assertFalse(result.credentialValuesChecked());
        assertFalse(result.connectionTested());
    }

    private void assertCode(ErrorCode expected) {
        assertEquals(expected, assertThrows(BusinessException.class,
            () -> service.check("project", "pinned-version")).getErrorCode());
    }
}
