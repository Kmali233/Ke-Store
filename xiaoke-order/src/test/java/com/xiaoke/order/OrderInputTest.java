package com.xiaoke.order;
import com.fasterxml.jackson.databind.*;
import com.xiaoke.common.config.JacksonInputConfig;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.order.dto.*;
import com.xiaoke.order.service.*;
import jakarta.validation.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

class OrderInputTest {
    static ValidatorFactory factory;
    static Validator validator;
    static ObjectMapper json;
    static final String ITEM="\"items\":[{\"productId\":1,\"quantity\":1}]";
    static final String SHIPPING="\"receiverName\":\" A \",\"receiverPhone\":\" 123 \",\"receiverAddress\":\" Road \"";
    @BeforeAll static void setup() {
        factory=Validation.buildDefaultValidatorFactory(); validator=factory.getValidator();
        var builder=Jackson2ObjectMapperBuilder.json(); new JacksonInputConfig().strictJson().customize(builder); json=builder.build();
    }
    @AfterAll static void close() { factory.close(); }
    @Test void twoShippingModesAndNormalization() throws Exception {
        var direct=json.readValue("{"+ITEM+","+SHIPPING+",\"remark\":\" \"}",OrderCreateDTO.class);
        assertThat(validator.validate(direct)).isEmpty(); assertThat(direct.getReceiverName()).isEqualTo("A"); assertThat(direct.getRemark()).isNull();
        assertThat(validator.validate(json.readValue("{"+ITEM+",\"addressId\":1}",OrderCreateDTO.class))).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={
        "", "\"addressId\":null", "\"addressId\":1,\"receiverName\":null",
        "\"receiverName\":null,\"receiverPhone\":\"1\",\"receiverAddress\":\"r\"",
        "\"receiverName\":\" \",\"receiverPhone\":\"1\",\"receiverAddress\":\"r\"",
        "\"receiverName\":\"x\",\"receiverPhone\":\"1\"", "\"addressId\":0"})
    void invalidShippingModes(String source) throws Exception {
        var dto=json.readValue("{"+ITEM+(source.isEmpty()?"":","+source)+"}",OrderCreateDTO.class);
        assertThat(validator.validate(dto)).isNotEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"null","[]","[null]","[{\"productId\":0,\"quantity\":1}]",
        "[{\"productId\":1,\"quantity\":0}]","[{\"productId\":1,\"quantity\":1000}]",
        "[{\"productId\":1,\"quantity\":1},{\"productId\":1,\"quantity\":2}]"})
    void invalidItems(String items) throws Exception {
        assertThat(validator.validate(json.readValue("{\"items\":"+items+","+SHIPPING+"}",OrderCreateDTO.class))).isNotEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"\"1\"","1.5","true","2147483648"})
    void quantityCannotCoerce(String value) {
        assertThatThrownBy(()->json.readValue("{\"items\":[{\"productId\":1,\"quantity\":"+value+"}],"+SHIPPING+"}",OrderCreateDTO.class))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
    @ParameterizedTest @ValueSource(strings={"userId","price","status","orderNo","totalAmount","deleted","createTime","addressProvided","shippingSourceValid"})
    void unknownAndServerFieldsCannotEnter(String field) {
        assertThatThrownBy(()->json.readValue("{"+ITEM+","+SHIPPING+",\""+field+"\":1}",OrderCreateDTO.class))
            .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
    @ParameterizedTest @ValueSource(strings={"20260230000001","20261301000001","00000101000001","2026102400000","202610240000001","2026a024000001"})
    void invalidOrderNumbers(String no) {
        assertThatThrownBy(()->new OrderInput(validator).orderNo(no)).isInstanceOf(BusinessException.class);
    }
    @Test void moneyDoesNotRoundAndChecksLineAndTotalBounds() {
        assertThat(OrderMoney.amount(new BigDecimal("1.23"),3,400)).isEqualByComparingTo("3.69");
        assertThat(OrderMoney.amount(new BigDecimal("99999999.99"),1,400)).isEqualByComparingTo("99999999.99");
        assertThatThrownBy(()->OrderMoney.amount(new BigDecimal("50000000.00"),2,400))
            .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(400));
        assertThatThrownBy(()->OrderMoney.price(new BigDecimal("1.234")))
            .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(500));
    }
}
