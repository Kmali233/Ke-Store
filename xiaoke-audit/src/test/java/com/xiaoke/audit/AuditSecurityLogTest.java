package com.xiaoke.audit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.xiaoke.common.auth.admin.*;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.*;
import org.springframework.web.method.HandlerMethod;
import static org.assertj.core.api.Assertions.*;

class AuditSecurityLogTest {
    static class Endpoint { @AdminPermission(AdminPermissions.MANAGE) public void write() { } }
    @Test void failedOperationLogsOnlyVerifiedIdentityAndFixedMetadata() throws Exception {
        Logger logger=(Logger)LoggerFactory.getLogger("xiaoke.security.audit");
        var capture=new ListAppender<ILoggingEvent>(); capture.start(); logger.addAppender(capture);
        try {
            var request=new MockHttpServletRequest("PUT","/admin/access/accounts/2/roles");
            request.addHeader("Authorization","Synthetic-Secret-Token");
            request.setQueryString("password=Synthetic-Secret-Password");
            request.setContent("Synthetic-Address-Payment-Key".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            request.setAttribute(AdminInterceptor.VERIFIED_PRINCIPAL,new AdminPrincipal(12,"hidden_username",0));
            var response=new MockHttpServletResponse(); response.setStatus(403); response.setHeader("X-Trace-Id","a".repeat(32));
            new AuditRequestInterceptor().afterCompletion(request,response,new HandlerMethod(new Endpoint(),Endpoint.class.getMethod("write")),
                    new RuntimeException("Synthetic-Exception-Secret"));
            assertThat(capture.list).hasSize(1);
            String line=capture.list.get(0).getFormattedMessage();
            assertThat(line).contains("actorId=12","admin:manage","REJECTED","http=403","a".repeat(32))
                    .doesNotContain("Synthetic","hidden_username","password","Address");
        } finally { logger.detachAppender(capture); capture.stop(); }
    }
    @Test void successfulBusinessWriteDoesNotBecomeASecondSuccessSecurityEvent() throws Exception {
        Logger logger=(Logger)LoggerFactory.getLogger("xiaoke.security.audit");
        var capture=new ListAppender<ILoggingEvent>(); capture.start(); logger.addAppender(capture);
        try {
            new AuditRequestInterceptor().afterCompletion(new MockHttpServletRequest("PUT","/admin/access/accounts/2/roles"),
                    new MockHttpServletResponse(),new HandlerMethod(new Endpoint(),Endpoint.class.getMethod("write")),null);
            assertThat(capture.list).isEmpty();
        } finally { logger.detachAppender(capture); capture.stop(); }
    }
}
