package com.xiaoke.order.service;
import com.xiaoke.common.exception.*;
import com.xiaoke.order.adapter.product.ProductDetailHttpClient;
import com.xiaoke.order.config.OrderProperties;
import com.xiaoke.order.dto.OrderCreateDTO;
import com.xiaoke.order.vo.OrderCreateVO;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionSystemException;
import java.sql.SQLException;
import java.time.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderCommandFacadeTest {
    OrderInput input=mock(OrderInput.class);
    OrderUsers users=mock(OrderUsers.class);
    ProductDetailHttpClient http=mock(ProductDetailHttpClient.class);
    OrderTransactionService writes=mock(OrderTransactionService.class);
    OrderTransactions transactions=mock(OrderTransactions.class);
    Clock clock=Clock.fixed(Instant.parse("2026-12-31T15:59:59Z"),ZoneOffset.UTC);
    OrderCommandFacade facade=new OrderCommandFacade(input,users,http,writes,transactions,clock,new OrderProperties());
    OrderCreateDTO dto=new OrderCreateDTO();
    { dto.setItems(java.util.List.of()); }
    DuplicateKeyException collision(String key) {
        return new DuplicateKeyException("test",new SQLException("Duplicate entry 'value' for key '"+key+"'","23000",1062));
    }
    @Test void onlyOrderNumberIndexRetriesAndFixedDayDoesNotChange() {
        var result=new OrderCreateVO("20261231000001",0,java.math.BigDecimal.ONE);
        when(writes.createOnce(eq(1L),same(dto),eq(LocalDate.of(2026,12,31))))
            .thenThrow(collision("t_orders.uk_order_no")).thenThrow(collision("uk_order_no")).thenReturn(result);
        assertThat(facade.create(1L,dto)).isEqualTo(result);
        verify(writes,times(3)).createOnce(eq(1L),same(dto),eq(LocalDate.of(2026,12,31))); verify(http,times(1)).preread(anyList());
    }
    @Test void threeCollisionsReturn409AndOtherIndexIsPreserved() {
        when(writes.createOnce(anyLong(),any(),any())).thenThrow(collision("uk_order_no"));
        assertThatThrownBy(()->facade.create(1L,dto)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(409));
        reset(writes);
        var other=collision("other_index"); when(writes.createOnce(anyLong(),any(),any())).thenThrow(other);
        assertThatThrownBy(()->facade.create(1L,dto)).isSameAs(other); verify(writes,times(1)).createOnce(anyLong(),any(),any());
    }
    @Test void unknownCommitOutcomeNeverRetriesEvenWithDuplicateCause() {
        var failure=new TransactionSystemException("unknown",collision("uk_order_no"));
        when(writes.createOnce(anyLong(),any(),any())).thenThrow(failure);
        assertThatThrownBy(()->facade.create(1L,dto)).isSameAs(failure);
        verify(writes,times(1)).createOnce(anyLong(),any(),any());
    }
    @Test void inputContainingIndexNameCannotMasqueradeAsCollision() {
        var misleading=new DuplicateKeyException("test",new SQLException("Duplicate entry 'uk_order_no' for key 'other'","23000",1062));
        assertThat(OrderCommandFacade.orderNumberCollision(misleading)).isFalse();
    }
    @Test void sameDayLockCoversEntireTransactionCall() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var secondHttp=new CountDownLatch(2);
        doAnswer(i->{ secondHttp.countDown(); return null; }).when(http).preread(anyList());
        when(writes.createOnce(eq(1L),any(),any())).thenAnswer(i->{
            entered.countDown(); if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
            return new OrderCreateVO("20261231000001",0,java.math.BigDecimal.ONE);
        });
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->facade.create(1L,dto)); assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            var second=pool.submit(()->facade.create(2L,dto)); assertThat(secondHttp.await(3,TimeUnit.SECONDS)).isTrue();
            verify(writes,never()).createOnce(eq(2L),any(),any());
            release.countDown(); first.get(5,TimeUnit.SECONDS); second.get(5,TimeUnit.SECONDS);
            verify(writes).createOnce(eq(2L),any(),any());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
