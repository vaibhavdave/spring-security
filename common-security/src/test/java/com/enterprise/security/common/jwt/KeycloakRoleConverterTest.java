package com.enterprise.security.common.jwt;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakRoleConverterTest {

    private final KeycloakRoleConverter converter = new KeycloakRoleConverter("order-service");

    @Test
    void mapsRealmRolesToRoleAuthorities() {
        Jwt jwt = jwtWithClaims(Map.of(
                "realm_access", Map.of("roles", List.of("ADMIN", "USER"))
        ));

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities())
                .extracting(Object::toString)
                .contains("ROLE_ADMIN", "ROLE_USER");
    }

    @Test
    void mapsClientSpecificResourceRolesForTheConfiguredClientOnly() {
        Jwt jwt = jwtWithClaims(Map.of(
                "resource_access", Map.of(
                        "order-service", Map.of("roles", List.of("SERVICE")),
                        "some-other-client", Map.of("roles", List.of("SHOULD_NOT_APPEAR")))
        ));

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities()).extracting(Object::toString)
                .contains("ROLE_SERVICE")
                .doesNotContain("ROLE_SHOULD_NOT_APPEAR");
    }

    @Test
    void toleratesMissingRoleClaims() {
        Jwt jwt = jwtWithClaims(Map.of());

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities()).isEmpty();
    }

    private Jwt jwtWithClaims(Map<String, Object> extraClaims) {
        return Jwt.withTokenValue("token-value")
                .header("alg", "none")
                .subject("user-123")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claims(claims -> claims.putAll(extraClaims))
                .build();
    }
}
