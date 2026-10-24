package com.xiaoke.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaoke.common.config.JacksonInputConfig;
import com.xiaoke.product.dto.*;
import jakarta.validation.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import static org.assertj.core.api.Assertions.*;

class CatalogInputTest {
    private static ValidatorFactory factory;
    private static Validator validator;
    private static ObjectMapper json;
    @BeforeAll static void setup() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
        var builder = Jackson2ObjectMapperBuilder.json();
        new JacksonInputConfig().strictJson().customize(builder);
        json = builder.build();
    }
    @AfterAll static void close() { factory.close(); }

    @Test void creationAndUpdateHaveDifferentRequiredFields() throws Exception {
        assertThat(validator.validate(json.readValue("{}", ProductCreateDTO.class))).hasSize(3);
        assertThat(validator.validate(json.readValue("{\"id\":1}", ProductUpdateDTO.class))).isEmpty();
        assertThat(validator.validate(json.readValue("{\"id\":1,\"name\":\"   \"}", ProductUpdateDTO.class))).isNotEmpty();
        assertThat(validator.validate(json.readValue("{}", CategoryCreateDTO.class))).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "orderCount", "sales", "deleted", "createTime", "update_time"})
    void creationRejectsServerOwnedFields(String field) {
        assertThatThrownBy(() -> json.readValue("{\"name\":\"x\",\"price\":0,\"stock\":1,\"" + field + "\":1}", ProductCreateDTO.class))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"1\"", "true", "1.5", "2147483648"})
    void stockRejectsCoercionAndOverflow(String stock) {
        assertThatThrownBy(() -> json.readValue("{\"name\":\"x\",\"price\":0,\"stock\":" + stock + "}", ProductCreateDTO.class))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "file:///a.jpg", "data:image/png;base64,xx", "//outside/a", "C:\\a.jpg", "https://"})
    void unsafeResourceLocationsAreRejected(String image) {
        var dto = new ProductUpdateDTO(); dto.setId(1L); dto.setImage(image);
        assertThat(validator.validate(dto)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/images/a.png", "images/商品.png", "https://example.com/a.png", "http://localhost/a"})
    void allowedResourceLocationsPass(String image) {
        var dto = new ProductUpdateDTO(); dto.setId(1L); dto.setImage(image);
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test void textUsesUtf8BytesAndStringsNormalizeBeforeLengthChecks() {
        var dto = new ProductUpdateDTO(); dto.setId(1L); dto.setName("  手机  ");
        dto.setDescription("中".repeat(21845));
        assertThat(dto.getName()).isEqualTo("手机");
        assertThat(validator.validate(dto)).isEmpty();
        dto.setDescription("中".repeat(21846));
        assertThat(validator.validate(dto)).isNotEmpty();
        var attr = new ProductAttrSaveDTO();
        attr.setAttrName("  名称  "); attr.setAttrValue("  值  "); attr.setAttrGroup("   ");
        assertThat(attr.getAttrGroup()).isNull();
        assertThat(validator.validate(attr)).isEmpty();
    }

    @Test void queryTrimsButSearchRequiresKeyword() {
        var page = new ProductQueryDTO(); page.setName("  ");
        assertThat(page.getName()).isNull();
        var search = new ProductSearchDTO(); search.setKeyword("  ");
        assertThat(validator.validate(search)).isNotEmpty();
        search.setKeyword(" x "); assertThat(search.getKeyword()).isEqualTo("x");
    }
}
