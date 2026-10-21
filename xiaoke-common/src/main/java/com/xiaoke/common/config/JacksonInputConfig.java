package com.xiaoke.common.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

@Configuration
public class JacksonInputConfig {
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictJson() {
        /** 配置 JSON 转换规则 */
        return builder -> {
            builder.serializationInclusion(JsonInclude.Include.ALWAYS);
            builder.timeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            builder.featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            builder.featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
            builder.postConfigurer(mapper -> {
                for (LogicalType type : new LogicalType[]{LogicalType.Integer, LogicalType.Float, LogicalType.Boolean}) {
                    var coercion = mapper.coercionConfigFor(type);
                    coercion.setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                            .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
                            .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
                }
                mapper.coercionConfigFor(LogicalType.Integer).setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
                mapper.coercionConfigFor(LogicalType.Boolean)
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
                mapper.coercionConfigFor(LogicalType.Textual)
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
            });
        };
    }
}
