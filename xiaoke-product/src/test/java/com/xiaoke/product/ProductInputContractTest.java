package com.xiaoke.product;

import com.xiaoke.common.config.JacksonInputConfig;
import com.xiaoke.common.exception.GlobalExceptionHandler;
import com.xiaoke.product.controller.ProductController;
import com.xiaoke.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 当前商品 DTO 接入 common 的 JSON/金额边界；管理门禁另有容器与真实HTTP测试。 */
class ProductInputContractTest {
    @Test
    void invalidMoneyNeverReachesProductService() throws Exception {
        var service = mock(ProductService.class);
        var builder = Jackson2ObjectMapperBuilder.json();
        new JacksonInputConfig().strictJson().customize(builder);
        var mvc = MockMvcBuilders.standaloneSetup(new ProductController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(builder.build()))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String price : new String[]{"1.234", "100000000", "\"1.20\"", "-1"}) {
            mvc.perform(post("/product").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"test\",\"stock\":1,\"price\":" + price + "}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        }
        verifyNoInteractions(service);
    }

    @Test
    void validMoneyIsPassedToServiceAsExactBigDecimal() throws Exception {
        var service = mock(ProductService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ProductController(service)).build();
        mvc.perform(post("/product").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"test\",\"stock\":1,\"price\":99999999.99}"))
                .andExpect(status().isOk());
        verify(service).addProduct(argThat(dto -> dto.getPrice().equals(new java.math.BigDecimal("99999999.99"))));
    }
}
