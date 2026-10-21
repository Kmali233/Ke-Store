package com.xiaoke.common.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "xiaoke.web")
public class CommonWebProperties {
    private List<String> allowedOrigins = new ArrayList<>();
    private long maxRequestBodyBytes = 1024 * 1024;

    @PostConstruct
    public void validate() {
        if (maxRequestBodyBytes <= 0 || maxRequestBodyBytes > Integer.MAX_VALUE || allowedOrigins == null) {
            throw new IllegalStateException("Invalid xiaoke.web configuration");
        }
        for (String origin : allowedOrigins) {
            try {
                URI uri = URI.create(origin);
                if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                        || uri.getHost() == null || uri.getRawUserInfo() != null
                        || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                        || uri.getQuery() != null || uri.getFragment() != null || origin.contains("*")) {
                    throw new IllegalArgumentException();
                }
            } catch (RuntimeException ex) {
                throw new IllegalStateException("allowed-origins must contain exact HTTP origins");
            }
        }
    }
}
