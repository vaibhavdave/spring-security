package com.enterprise.security.common.apikey;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Demo-grade API key registry for partner integrations, configured via
 * "security.api-keys.<partner-id>.key/authorities". Production systems would back this with a
 * vault-managed secret store and hashed key comparison rather than plaintext application config.
 */
@ConfigurationProperties(prefix = "security")
public class ApiKeyProperties {

    private Map<String, Entry> apiKeys = Map.of();

    public Map<String, Entry> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(Map<String, Entry> apiKeys) {
        this.apiKeys = apiKeys;
    }

    public static class Entry {
        private String key;
        private List<String> authorities = List.of();

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public List<String> getAuthorities() {
            return authorities;
        }

        public void setAuthorities(List<String> authorities) {
            this.authorities = authorities;
        }
    }
}
