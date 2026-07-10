package com.enterprise.security.common.config;

import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;

/**
 * Hardened header baseline shared across every service: HSTS, a restrictive CSP (services are
 * APIs, not HTML renderers, so "default-src 'none'" is safe), no framing, no referrer leakage,
 * and no legacy caches of authenticated responses.
 */
public final class SecurityHeadersCustomizer {

    private SecurityHeadersCustomizer() {
    }

    public static Customizer<HeadersConfigurer<HttpSecurity>> apiDefaults() {
        return headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                .frameOptions(frame -> frame.deny())
                .referrerPolicy(referrer -> referrer
                        .policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .httpStrictTransportSecurity(hsts -> hsts
                        .includeSubDomains(true)
                        .maxAgeInSeconds(31536000))
                .permissionsPolicy(permissions -> permissions
                        .policy("geolocation=(), camera=(), microphone=()"));
    }
}
