package com.enterprise.security.common.jwt;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps Keycloak's token shape (realm_access.roles + resource_access.&lt;client&gt;.roles)
 * onto Spring's GrantedAuthority model, rather than relying on the generic "scope"
 * claim that JwtGrantedAuthoritiesConverter uses by default.
 */
public class KeycloakRoleConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final String resourceClientId;
    private final JwtGrantedAuthoritiesConverter scopeAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
    private final JwtAuthenticationConverter delegate;

    public KeycloakRoleConverter(String resourceClientId) {
        this.resourceClientId = resourceClientId;
        this.delegate = new JwtAuthenticationConverter();
        this.delegate.setJwtGrantedAuthoritiesConverter(this::extractAuthorities);
    }

    private Collection<GrantedAuthority> extractAuthorities(Jwt jwt) {
        Set<GrantedAuthority> authorities = new HashSet<>(scopeAuthoritiesConverter.convert(jwt));
        authorities.addAll(realmRoles(jwt));
        authorities.addAll(resourceRoles(jwt));
        return authorities;
    }

    @SuppressWarnings("unchecked")
    private Set<GrantedAuthority> realmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof List<?> roles)) {
            return Set.of();
        }
        return roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .collect(java.util.stream.Collectors.toSet());
    }

    @SuppressWarnings("unchecked")
    private Set<GrantedAuthority> resourceRoles(Jwt jwt) {
        Map<String, Object> resourceAccess = jwt.getClaim("resource_access");
        if (resourceAccess == null || !(resourceAccess.get(resourceClientId) instanceof Map<?, ?> clientAccess)) {
            return Set.of();
        }
        Object roles = clientAccess.get("roles");
        if (!(roles instanceof List<?> roleList)) {
            return Set.of();
        }
        return roleList.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .collect(java.util.stream.Collectors.toSet());
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt source) {
        return delegate.convert(source);
    }
}
