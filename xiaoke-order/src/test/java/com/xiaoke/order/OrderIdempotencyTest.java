package com.xiaoke.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.order.dto.*;
import com.xiaoke.order.idempotency.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderIdempotencyTest {
    OrderIdempotency service=new OrderIdempotency(mock(OrderIdempotencyMapper.class),new ObjectMapper());
    OrderCreateDTO manual() {
        var dto=new OrderCreateDTO();var p=new OrderItemCreateDTO();p.setProductId(1L);p.setQuantity(2);
        var q=new OrderItemCreateDTO();q.setProductId(2L);q.setQuantity(1);dto.setItems(List.of(p,q));
        dto.setReceiverName(" A ");dto.setReceiverPhone("1");dto.setReceiverAddress("Road");return dto;
    }
    String hash(OrderCreateDTO dto) {return service.request("key",dto).hash();}

    @Test void versionOneCanonicalHashRemainsStableAcrossRefactors() {
        // Contract bytes: [1,[[1,2],[2,1]],["manual","A","1","Road"],null]
        assertThat(hash(manual())).isEqualTo("60236537105d473e6d2c875909fd5bbc8e933748d55bb7cbb9ee6780d059e67e");
    }

    @Test void normalizationIgnoresItemOrderShippingWhitespaceAndBlankRemark() {
        var left=manual();var right=manual();right.setItems(List.of(right.getItems().get(1),right.getItems().get(0)));
        right.setReceiverName("A");right.setRemark("  ");assertThat(hash(left)).isEqualTo(hash(right));
        assertThat(hash(left)).hasSize(64);
        assertThat(service.request("other-key",left).hash()).isEqualTo(hash(left));
    }
    @Test void everyClientIntentFieldChangesHash() {
        var original=hash(manual());
        var quantity=manual();quantity.getItems().get(0).setQuantity(3);
        var product=manual();product.getItems().get(0).setProductId(3L);
        var name=manual();name.setReceiverName("B");var phone=manual();phone.setReceiverPhone("2");
        var address=manual();address.setReceiverAddress("Other Road");var remark=manual();remark.setRemark("note");
        for(var changed:List.of(quantity,product,name,phone,address,remark)) assertThat(hash(changed)).isNotEqualTo(original);
    }
    @Test void addressReferenceAndManualShippingHaveDistinctIdentities() {
        var ref=new OrderCreateDTO();ref.setItems(manual().getItems());ref.setAddressId(1L);
        assertThat(hash(ref)).isNotEqualTo(hash(manual()));var first=hash(ref);ref.setAddressId(2L);
        assertThat(hash(ref)).isNotEqualTo(first);
    }
    @Test void delimiterLikeTextCannotCauseHashCollision() {
        var left=manual();left.setReceiverName("A|B");left.setReceiverPhone("C");
        var right=manual();right.setReceiverName("A");right.setReceiverPhone("B|C");
        assertThat(hash(left)).isNotEqualTo(hash(right));
    }
    @Test void keysHaveStrictAsciiLengthAndAreNotTrimmedOrLowercased() {
        for(String key:Arrays.asList(null,""," key","key ","a,b","a/b","中文","x".repeat(65))) {
            assertThatThrownBy(()->service.key(key)).isInstanceOf(BusinessException.class);
        }
        assertThat(service.key("Aa_09-".repeat(10))).isEqualTo("Aa_09-".repeat(10));
        assertThat(service.key("x".repeat(64))).hasSize(64);
    }
    @Test void unknownStoredHashVersionBlocksReuse() {
        var mapper=mock(OrderIdempotencyMapper.class);var row=new OrderIdempotencyRow();row.setHashVersion(2);
        when(mapper.find(1L,"key")).thenReturn(row);
        var local=new OrderIdempotency(mapper,new ObjectMapper());
        assertThatThrownBy(()->local.replay(1L,local.request("key",manual()),false))
            .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(409));
    }
    @Test void skuRequestsHaveVersionTwoAndTaggedSortedIdsWhileLegacyStaysVersionOne() {
        var left=manual();left.getItems().get(0).setSkuId(1L);left.getItems().get(0).setProductId(null);
        var right=manual();right.getItems().get(0).setSkuId(1L);right.getItems().get(0).setProductId(null);
        right.setItems(List.of(right.getItems().get(1),right.getItems().get(0)));
        assertThat(service.request("key",left).version()).isEqualTo(2);assertThat(hash(left)).isEqualTo(hash(right));
        assertThat(hash(left)).isNotEqualTo(hash(manual()));assertThat(service.request("key",manual()).version()).isEqualTo(1);
    }
}
