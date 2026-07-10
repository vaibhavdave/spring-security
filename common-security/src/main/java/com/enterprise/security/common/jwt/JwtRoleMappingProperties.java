package com.enterprise.security.common.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Names the Keycloak client whose resource_access.<client>.roles should also become GrantedAuthority. */
@ConfigurationProperties(prefix = "security.resource")
public class JwtRoleMappingProperties {

    private String clientId = "";

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }
}
