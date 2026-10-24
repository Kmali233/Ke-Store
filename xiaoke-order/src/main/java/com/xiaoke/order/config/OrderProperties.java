package com.xiaoke.order.config;
import jakarta.validation.constraints.*;
import lombok.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix="xiaoke.order")   // 表示把配置文件中这一部分的值绑定进来
public class OrderProperties {
    @NotBlank
    private String productBaseUrl;

    @NotNull @Pattern(regexp="Asia/Shanghai")
    private String zoneId="Asia/Shanghai";

    @Min(1) @Max(1000)
    private int connectTimeoutMs=1000;

    @Min(1) @Max(2000)
    private int readTimeoutMs=2000;

    @Min(1) @Max(5000)
    private int prereadDeadlineMs=5000;
}
