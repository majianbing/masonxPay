package com.masonx.virtualaccount.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "va.signature")
public class LedgerSignatureProperties {

    private String secret;
    private String activeKeyId = "default";
    private Map<String, String> keys = new HashMap<>();

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getActiveKeyId() {
        return activeKeyId;
    }

    public void setActiveKeyId(String activeKeyId) {
        this.activeKeyId = activeKeyId;
    }

    public Map<String, String> getKeys() {
        return keys;
    }

    public void setKeys(Map<String, String> keys) {
        this.keys = keys != null ? keys : new HashMap<>();
    }

    public Map<String, String> resolvedKeys() {
        Map<String, String> resolved = new HashMap<>(keys);
        if (secret != null && !secret.isBlank()) {
            resolved.putIfAbsent("default", secret);
        }
        return resolved;
    }
}
