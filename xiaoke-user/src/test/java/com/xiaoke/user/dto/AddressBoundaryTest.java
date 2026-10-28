package com.xiaoke.user.dto;
import jakarta.validation.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.BeanWrapperImpl;
import static org.assertj.core.api.Assertions.*;

/** 按接口契约逐字段核对VARCHAR边界，避免只检查组合地址长度。 */
class AddressBoundaryTest {
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
    @ParameterizedTest @CsvSource({"receiverName,50","receiverPhone,20","province,50","city,50","district,50","detailAddress,255"})
    void addressFieldLimits(String field,int limit) {
        var dto=new AddressCreateDTO();dto.setReceiverName("A");dto.setReceiverPhone("1");dto.setDetailAddress("R");
        boundary(dto,field,limit);
    }
}
