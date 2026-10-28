package com.xiaoke.product;
import com.xiaoke.product.dto.*;
import jakarta.validation.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.BeanWrapperImpl;
import static org.assertj.core.api.Assertions.*;

class CatalogBoundaryTest {
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
    @ParameterizedTest @CsvSource({"product,name,128","product,image,255","product,description,65535",
        "category,name,50","category,icon,255","attr,attrName,50","attr,attrValue,255","attr,attrGroup,50",
        "search,keyword,128","query,name,128"})
    void catalogFieldLimits(String kind,String field,int limit) {
        Object dto;
        if(kind.equals("product")) {var p=new ProductCreateDTO();p.setName("p");p.setPrice(java.math.BigDecimal.ONE);p.setStock(0);dto=p;}
        else if(kind.equals("category")) {var c=new CategoryCreateDTO();c.setName("c");dto=c;}
        else if(kind.equals("search")) {dto=new ProductSearchDTO();}
        else if(kind.equals("query")) {dto=new ProductQueryDTO();}
        else {var a=new ProductAttrSaveDTO();a.setAttrName("a");a.setAttrValue("v");dto=a;}
        boundary(dto,field,limit);
    }
}
