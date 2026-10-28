package com.xiaoke.common.auth.admin;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "xiaoke.admin")
public class AdminProperties {
    private boolean enabled;
    private String secretBase64;
}
