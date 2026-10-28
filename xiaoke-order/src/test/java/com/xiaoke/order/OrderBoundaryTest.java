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
    @ParameterizedTest @CsvSource({"create,receiverName,50","create,receiverPhone,20","create,receiverAddress,255",
        "create,remark,255","ship,logisticsCompany,50","ship,trackingNo,64"})
    void shippingFieldLimits(String kind,String field,int limit) {
        Object dto;
        if(kind.equals("ship")) {var s=new OrderShipDTO();s.setLogisticsCompany("c");s.setTrackingNo("t");dto=s;}
        else {
            var c=new OrderCreateDTO();var i=new OrderItemCreateDTO();i.setProductId(1L);i.setQuantity(1);
            c.setItems(java.util.List.of(i));c.setReceiverName("A");c.setReceiverPhone("1");c.setReceiverAddress("R");dto=c;
        }
        boundary(dto,field,limit);
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
