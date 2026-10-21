package com.xiaoke.common.web;

import com.xiaoke.common.config.CommonWebProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;

import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestBodyLimitAdviceTest {
    @Test
    void rejectsUnknownLengthStreamAfterLimit() throws Exception {
        var props = new CommonWebProperties(); props.setMaxRequestBodyBytes(8);
        var advice = new RequestBodyLimitAdvice(props);
        var input = new MockHttpInputMessage(new byte[9]);
        input.getHeaders().setContentLength(-1);
        var wrapped = advice.beforeBodyRead(input, null, Object.class, MappingJackson2HttpMessageConverter.class);
        assertThrows(RequestBodyLimitAdvice.BodyTooLargeException.class, () -> wrapped.getBody().readAllBytes());
    }
}
