package com.enterprise.security.common.config;

import com.enterprise.security.common.apikey.ApiKeyAuthFilter;
import com.enterprise.security.common.apikey.ApiKeyProperties;
import com.enterprise.security.common.audit.AuditLogAspect;
import com.enterprise.security.common.error.GlobalExceptionHandler;
import com.enterprise.security.common.error.RestAccessDeniedHandler;
import com.enterprise.security.common.error.RestAuthenticationEntryPoint;
import com.enterprise.security.common.jwt.JwtRoleMappingProperties;
import com.enterprise.security.common.jwt.KeycloakRoleConverter;
import com.enterprise.security.common.tenant.TenantContextFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Wires the shared security building blocks (Keycloak role mapping, tenant context propagation,
 * API key auth, consistent error contracts, audit logging) as beans that each resource service's
 * SecurityConfig composes into its own HttpSecurity filter chain.
 */
@AutoConfiguration
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true)
@EnableConfigurationProperties({ApiKeyProperties.class, JwtRoleMappingProperties.class})
public class CommonSecurityAutoConfiguration {

    @Bean
    public KeycloakRoleConverter keycloakRoleConverter(JwtRoleMappingProperties props) {
        return new KeycloakRoleConverter(props.getClientId());
    }

    @Bean
    public RestAuthenticationEntryPoint restAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return new RestAuthenticationEntryPoint(objectMapper);
    }

    @Bean
    public RestAccessDeniedHandler restAccessDeniedHandler(ObjectMapper objectMapper) {
        return new RestAccessDeniedHandler(objectMapper);
    }

    @Bean
    public TenantContextFilter tenantContextFilter() {
        return new TenantContextFilter();
    }

    @Bean
    public ApiKeyAuthFilter apiKeyAuthFilter(ApiKeyProperties apiKeyProperties) {
        return new ApiKeyAuthFilter(apiKeyProperties);
    }

    @Bean
    public AuditLogAspect auditLogAspect() {
        return new AuditLogAspect();
    }

    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
