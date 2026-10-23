package com.xiaoke.user.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaoke.common.config.JacksonInputConfig;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import static org.assertj.core.api.Assertions.*;

class AddressDTOTest {
    static ValidatorFactory factory;
    static ObjectMapper mapper;

    @BeforeAll
    static void before() {
        factory = Validation.buildDefaultValidatorFactory();
        var builder = new Jackson2ObjectMapperBuilder();
        new JacksonInputConfig().strictJson().customize(builder);
        mapper = builder.build();
    }

    @AfterAll static void after() { factory.close(); }

    @Test
    void omittedDefaultIsDistinctFromExplicitNull() throws Exception {
        var value = mapper.readValue("""
                {"receiverName":" 张三 ","receiverPhone":" +86 123 ","detailAddress":" 路1号 ","city":" "}
                """, AddressCreateDTO.class);
        assertThat(value.getIsDefault()).isNull();
        assertThat(value.getReceiverName()).isEqualTo("张三");
        assertThat(value.getCity()).isNull();
        assertThat(factory.getValidator().validate(value)).isEmpty();
        assertThatThrownBy(() -> mapper.readValue("{\"isDefault\":null}", AddressCreateDTO.class))
                .isInstanceOf(JsonProcessingException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"isDefault\":1}", "{\"isDefault\":\"true\"}", "{\"userId\":1}",
            "{\"id\":1}", "{\"receiverPhone\":123}", "{\"combinedAddressValid\":true}"})
    void strictInputRejectsWrongTypesAndExtraProperties(String json) {
        assertThatThrownBy(() -> mapper.readValue(json, AddressCreateDTO.class))
                .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void checksJoinedLengthAndUpdateRequiredFields() {
        var value = new AddressCreateDTO();
        value.setReceiverName("张三");
        value.setReceiverPhone("123");
        value.setProvince("省");
        value.setDetailAddress("路".repeat(253));
        assertThat(factory.getValidator().validate(value)).isEmpty();
        value.setDetailAddress("路".repeat(254));
        assertThat(factory.getValidator().validate(value)).isNotEmpty();
        assertThat(factory.getValidator().validate(new AddressUpdateDTO())).isNotEmpty();
    }
}
