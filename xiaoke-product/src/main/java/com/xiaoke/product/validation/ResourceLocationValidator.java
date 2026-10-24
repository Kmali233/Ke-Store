package com.xiaoke.product.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * 检查图片、图标的地址格式
 */
public class ResourceLocationValidator
        implements ConstraintValidator<ResourceLocation, String> {

    /**
     * 规则：只允许以下格式通过
     * https://example.com/a.png	通过	HTTPS 地址，具有主机名
     * http://localhost/a.png	通过	HTTP 地址
     * /images/a.png	通过	站内路径
     * images/a.png	通过	相对路径
     */
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isEmpty()) { return true; }
        if (value.startsWith("//") || value.contains("\\") || value.chars().anyMatch(Character::isISOControl)) {
            return false;
        }
        try {
            URI uri = new URI(value);
            if (!uri.isAbsolute()) { return uri.getRawAuthority() == null; }
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (URISyntaxException failure) { return false; }
    }
}
