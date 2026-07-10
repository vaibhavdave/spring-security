package com.enterprise.security.common.apikey;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * Authenticates requests carrying an X-API-Key header against the configured partner registry.
 * Only runs for requests that present the header; everything else falls through to the normal
 * JWT bearer-token authentication so both schemes can coexist on the same service.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-API-Key";

    private final Map<String, ApiKeyProperties.Entry> apiKeysByPartner;

    public ApiKeyAuthFilter(ApiKeyProperties properties) {
        this.apiKeysByPartner = properties.getApiKeys();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String presentedKey = request.getHeader(HEADER_NAME);
        if (StringUtils.hasText(presentedKey)) {
            apiKeysByPartner.entrySet().stream()
                    .filter(e -> constantTimeEquals(e.getValue().getKey(), presentedKey))
                    .findFirst()
                    .ifPresent(e -> authenticate(e.getKey(), e.getValue()));
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(String partnerId, ApiKeyProperties.Entry entry) {
        List<GrantedAuthority> authorities = entry.getAuthorities().stream()
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new ApiKeyAuthenticationToken(partnerId, entry.getKey(), authorities));
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(), actual.getBytes());
    }
}
