package com.xiaoke.order;
import com.xiaoke.order.dto.*;
import com.xiaoke.common.config.JacksonInputConfig;
import jakarta.validation.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.BeanWrapperImpl;
import static org.assertj.core.api.Assertions.*;

class OrderBoundaryTest {
    static ValidatorFactory factory;
    @BeforeAll static void setup() { factory=Validation.buildDefaultValidatorFactory(); }
    @AfterAll static void close() { factory.close(); }
    void boundary(Object dto,String field,int limit) {
        var bean=new BeanWrapperImpl(dto);
        bean.setPropertyValue(field,"a".repeat(limit));
        assertThat(factory.getValidator().validate(dto)).as(field+" at limit").isEmpty();
        bean.setPropertyValue(field,"a".repeat(limit+1));
        assertThat(factory.getValidator().validate(dto)).as(field+" beyond limit").isNotEmpty();
    }
    @ParameterizedTest @CsvSource({"receiverName,50","receiverPhone,20","receiverAddress,255","remark,255"})
    void legacyFieldsAndRemarkLimits(String field,int limit) {
        var order=new OrderCreateDTO();var item=new OrderItemCreateDTO();item.setProductId(1L);item.setQuantity(1);
        order.setItems(java.util.List.of(item));
        order.setReceiverName("A");order.setReceiverPhone("1");order.setReceiverAddress("R");
        boundary(order,field,limit);
    }
    @Test void productIdLongBoundaryIsStrict() throws Exception {
        var builder=org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json();
        new JacksonInputConfig().strictJson().customize(builder);var mapper=builder.build();
        String prefix="{\"items\":[{\"productId\":";
        String suffix=",\"quantity\":1}],\"addressId\":1}";
        assertThat(factory.getValidator().validate(mapper.readValue(prefix+Long.MAX_VALUE+suffix,OrderCreateDTO.class))).isEmpty();
        assertThatThrownBy(()->mapper.readValue(prefix+"9223372036854775808"+suffix,OrderCreateDTO.class))
            .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
}
