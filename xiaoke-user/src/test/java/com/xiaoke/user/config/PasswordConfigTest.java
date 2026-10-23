package com.xiaoke.user.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

/** 只加载密码配置，不启动 Web 服务，也不连接数据库。 */
@SpringJUnitConfig(PasswordConfig.class)
class PasswordConfigTest {

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void encodesPasswordWithCostTenAndFitsDatabaseColumn() {
        String rawPassword = "abc12345";
        String hash = passwordEncoder.encode(rawPassword);

        assertThat(hash).isNotEqualTo(rawPassword);
        assertThat(hash).matches("\\$2[aby]\\$10\\$[./A-Za-z0-9]{53}");
        assertThat(hash.length()).isLessThanOrEqualTo(100);
        assertThat(passwordEncoder.matches(rawPassword, hash)).isTrue();
        assertThat(passwordEncoder.matches("wrong-password", hash)).isFalse();
    }

    @Test
    void samePasswordProducesDifferentHashesThatBothMatch() {
        String rawPassword = "abc12345";
        String first = passwordEncoder.encode(rawPassword);
        String second = passwordEncoder.encode(rawPassword);

        assertThat(first).isNotEqualTo(second);
        assertThat(passwordEncoder.matches(rawPassword, first)).isTrue();
        assertThat(passwordEncoder.matches(rawPassword, second)).isTrue();
    }

    @Test
    void preservesPasswordSpacesAndCase() {
        String rawPassword = " Abc123 ";
        String hash = passwordEncoder.encode(rawPassword);

        assertThat(passwordEncoder.matches(rawPassword, hash)).isTrue();
        assertThat(passwordEncoder.matches("Abc123", hash)).isFalse();
        assertThat(passwordEncoder.matches(" abc123 ", hash)).isFalse();
    }

    @Test
    void acceptsMultibytePasswordWithinDtoLengthLimit() {
        String rawPassword = "密".repeat(20);
        String hash = passwordEncoder.encode(rawPassword);

        assertThat(passwordEncoder.matches(rawPassword, hash)).isTrue();
        assertThat(passwordEncoder.matches("密".repeat(19), hash)).isFalse();
    }
}
