package com.enterprise.security.adminservice.config;

import com.enterprise.security.common.apikey.ApiKeyAuthFilter;
import com.enterprise.security.common.config.SecurityHeadersCustomizer;
import com.enterprise.security.common.error.RestAccessDeniedHandler;
import com.enterprise.security.common.error.RestAuthenticationEntryPoint;
import com.enterprise.security.common.jwt.KeycloakRoleConverter;
import com.enterprise.security.common.tenant.TenantContextFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * admin-service accepts two independent credential types on the same filter chain: Keycloak JWTs
 * for /api/** (internal staff) and X-API-Key for /partner/** (external integrations). The API key
 * filter runs first and only sets an Authentication when its header is present, so it never
 * interferes with normal bearer-token requests.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                            KeycloakRoleConverter keycloakRoleConverter,
                                            TenantContextFilter tenantContextFilter,
                                            ApiKeyAuthFilter apiKeyAuthFilter,
                                            RestAuthenticationEntryPoint authenticationEntryPoint,
                                            RestAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(SecurityHeadersCustomizer.apiDefaults())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/partner/**").hasRole("PARTNER")
                        .anyRequest().authenticated())
                .oauth2ResourceServer((OAuth2ResourceServerConfigurer<HttpSecurity> oauth2) -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakRoleConverter))
                        .authenticationEntryPoint(authenticationEntryPoint))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(apiKeyAuthFilter, BearerTokenAuthenticationFilter.class)
                .addFilterAfter(tenantContextFilter, BearerTokenAuthenticationFilter.class);

        return http.build();
    }
}
