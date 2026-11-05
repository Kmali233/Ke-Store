package com.xiaoke.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaoke.audit.AuditChanges.Field;
import com.xiaoke.common.auth.admin.*;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.xiaoke.audit.AuditChanges.Field.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AuditContractTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final Clock clock=Clock.fixed(Instant.parse("2026-10-29T01:02:03.123456789Z"),ZoneOffset.UTC);
    AuditWriter writer() { return new AuditWriter(jdbc,new ObjectMapper(),clock,new MockEnvironment().withProperty("spring.application.name","xiaoke-test")); }
    @AfterEach void clean() {
        AdminContext.clear(); MDC.clear(); TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }
    @Test void payloadRejectsFreeTextInsteadOfTryingToMaskArbitraryDtos() {
        for(var field:Field.values()) assertThatThrownBy(()->AuditChanges.start().put(field,null,"Synthetic-Password-123"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->AuditChanges.start().put(ROLE_IDS,List.of(),List.of("token"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void priceAndRoleNormalizationSuppressNoopEvents() {
        assertThat(AuditChanges.start().put(PRICE,new BigDecimal("1.00"),new BigDecimal("1.0"))
                .put(ROLE_IDS,List.of(2L,3L),List.of(3L,2L)).empty()).isTrue();
    }
    @Test void writableOwningTransactionIsMandatory() {
        assertThatThrownBy(()->writer().record(AuditAction.ORDER_SHIP,"20261029000001",AuditChanges.start().put(STATUS,1,3)))
                .isInstanceOf(IllegalStateException.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatThrownBy(()->writer().record(AuditAction.PRODUCT_DELETE,1,AuditChanges.start().put(DELETED,0,1)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(jdbc);
    }
    @Test void writerCapturesTrustedActorTraceAndUtcMicroseconds() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        AdminContext.set(new AdminPrincipal(12,"catalog_admin",0)); MDC.put("traceId","a".repeat(32));
        writer().record(AuditAction.PRODUCT_PRICE_CHANGE,100,AuditChanges.start().put(PRICE,new BigDecimal("1"),new BigDecimal("2")));
        Object[] args=(Object[])mockingDetails(jdbc).getInvocations().iterator().next().getRawArguments()[1];
        assertThat(args[1]).isEqualTo("ADMIN"); assertThat(args[2]).isEqualTo(12L);
        assertThat(args[7]).isEqualTo("a".repeat(32));
        assertThat(args[9]).isEqualTo(LocalDateTime.parse("2026-10-29T01:02:03.123456"));
        assertThat(args[6].toString()).doesNotContain("catalog_admin","password","token");
    }
    @Test void internalCallHasExplicitSystemActorAndNoopDoesNotInsert() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        writer().record(AuditAction.PRODUCT_UPDATE,1,AuditChanges.start()); verifyNoInteractions(jdbc);
        writer().record(AuditAction.PRODUCT_DELETE,1,AuditChanges.start().put(DELETED,0,1));
        Object[] args=(Object[])mockingDetails(jdbc).getInvocations().iterator().next().getRawArguments()[1];
        assertThat(args[1]).isEqualTo("SYSTEM"); assertThat(args[2]).isNull();
    }
    @Test void databaseFailurePropagatesToBusinessTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(jdbc.update(anyString(),any(Object[].class))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic"));
        assertThatThrownBy(()->writer().record(AuditAction.PRODUCT_DELETE,1,AuditChanges.start().put(DELETED,0,1)))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
    }
    @Test void checksumSurvivesMysqlJsonFormattingAndDetectsPayloadModification() {
        var time=LocalDateTime.parse("2026-10-29T01:02:03");
        String first=AuditIntegrity.digest("key","ADMIN",1L,"PRODUCT_UPDATE","PRODUCT","1","{\"X\":{\"before\":1,\"after\":2}}","a".repeat(32),"xiaoke-test",time);
        String formatted=AuditIntegrity.digest("key","ADMIN",1L,"PRODUCT_UPDATE","PRODUCT","1","{ \"X\": {\"after\": 2, \"before\": 1} }","a".repeat(32),"xiaoke-test",time);
        String changed=AuditIntegrity.digest("key","ADMIN",1L,"PRODUCT_UPDATE","PRODUCT","1","{\"X\":{\"before\":1,\"after\":9}}","a".repeat(32),"xiaoke-test",time);
        assertThat(first).isEqualTo(formatted).isNotEqualTo(changed);
    }
}
