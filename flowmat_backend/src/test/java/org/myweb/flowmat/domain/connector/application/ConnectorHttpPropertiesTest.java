package org.myweb.flowmat.domain.connector.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConnectorHttpPropertiesTest {
    @Test
    void networkAccessAndCredentialsRequireExplicitDeploymentConfiguration() {
        ConnectorHttpProperties properties = new ConnectorHttpProperties();

        assertFalse(properties.isEnabled());
        assertFalse(properties.isAllowPrivateHttp());
        assertTrue(properties.getAllowedOrigins().isEmpty());
        assertTrue(properties.getPrivateCidrs().isEmpty());
        assertTrue(properties.getCredentials().isEmpty());
    }
}
