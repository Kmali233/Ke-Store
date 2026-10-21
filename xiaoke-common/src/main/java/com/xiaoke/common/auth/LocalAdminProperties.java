package com.xiaoke.common.auth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "xiaoke")
public class LocalAdminProperties {
    private boolean localAdminEnabled;
    private String localAdminKey;
}
