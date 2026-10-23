package com.xiaoke.user.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaoke.common.config.JacksonInputConfig;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegisterDTOTest {
    private static ValidatorFactory factory;
    private static Validator validator;
    private static ObjectMapper mapper;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
        var builder = new Jackson2ObjectMapperBuilder();
        new JacksonInputConfig().strictJson().customize(builder);
        mapper = builder.build();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void normalizesRequestFieldsBeforeValidationAndPreservesPassword() throws Exception {
        var dto = mapper.readValue("""
                {"username":" Test_User ","password":" abc123 ",
                 "nickname":" 测试用户 ","phone":" 13800000001 "}
                """, RegisterDTO.class);
        assertThat(dto.getUsername()).isEqualTo("Test_User");
        assertThat(dto.getPassword()).isEqualTo(" abc123 ");
        assertThat(dto.getNickname()).isEqualTo("测试用户");
        assertThat(dto.getPhone()).isEqualTo("13800000001");
        assertThat(validator.validate(dto)).isEmpty();
        assertThat(mapper.valueToTree(dto).has("password")).isFalse();
        assertThat(dto.toString()).doesNotContain(dto.getPassword());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"nickname\":null,\"phone\":null}",
            "{\"nickname\":\"  \",\"phone\":\" \"}"})
    void optionalFieldsCanBeMissingNullOrBlank(String json) throws Exception {
        var dto = mapper.readValue(json, RegisterDTO.class);
        dto.setUsername("test_user");
        dto.setPassword("abc12345");
        assertThat(dto.getNickname()).isNull();
        assertThat(dto.getPhone()).isNull();
        assertThat(validator.validate(dto)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("usernames")
    void validatesUsernameBoundariesAndAsciiBeforeLowercasing(String username, boolean valid) {
        var dto = new RegisterDTO();
        dto.setUsername(username);
        assertThat(validator.validateProperty(dto, "username").isEmpty()).isEqualTo(valid);
    }

    static Stream<Arguments> usernames() {
        return Stream.of(
                Arguments.of(null, false), Arguments.of("   ", false),
                Arguments.of("abc", false), Arguments.of("Ab_1", true),
                Arguments.of("A".repeat(50), true), Arguments.of("a".repeat(51), false),
                Arguments.of(" Test_User ", true), Arguments.of("user-name", false),
                Arguments.of("测试用户", false), Arguments.of("Kevin", false));
    }

    @ParameterizedTest
    @MethodSource("passwords")
    void validatesPasswordLengthWithoutTrimming(String password, boolean valid) {
        var dto = new RegisterDTO();
        dto.setPassword(password);
        assertThat(dto.getPassword()).isEqualTo(password);
        assertThat(validator.validateProperty(dto, "password").isEmpty()).isEqualTo(valid);
    }

    static Stream<Arguments> passwords() {
        return Stream.of(
                Arguments.of(null, false), Arguments.of("12345", false),
                Arguments.of("123456", true), Arguments.of("p".repeat(20), true),
                Arguments.of("p".repeat(21), false), Arguments.of("      ", true),
                Arguments.of(" abc12 ", true));
    }

    @Test
    void validatesOptionalLengthsAfterTrimming() {
        var dto = new RegisterDTO();
        dto.setNickname(" " + "昵".repeat(50) + " ");
        dto.setPhone(" " + "1".repeat(20) + " ");
        assertThat(validator.validateProperty(dto, "nickname")).isEmpty();
        assertThat(validator.validateProperty(dto, "phone")).isEmpty();
        dto.setNickname("昵".repeat(51));
        dto.setPhone("1".repeat(21));
        assertThat(validator.validateProperty(dto, "nickname")).isNotEmpty();
        assertThat(validator.validateProperty(dto, "phone")).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"userId\":1}", "{\"id\":1}", "{\"status\":1}",
            "{\"deleted\":0}", "{\"username\":1234}", "{\"password\":123456}",
            "{\"nickname\":true}", "{\"phone\":13800000001}",
            "{\"username\":\"test1\",\"username\":\"test2\"}"})
    void usesCommonStrictJsonRules(String json) {
        assertThatThrownBy(() -> mapper.readValue(json, RegisterDTO.class))
                .isInstanceOf(JsonProcessingException.class);
    }
}
