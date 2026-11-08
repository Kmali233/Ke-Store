package com.xiaoke.common.inventory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class InventoryLedgerTest {
    @Test void journalCannotBeWrittenOutsideBusinessTransaction() {
        var jdbc=mock(JdbcTemplate.class);var ledger=new InventoryLedger(jdbc);
        assertThatThrownBy(()->ledger.opening(1,10,0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->ledger.changed(1,InventoryLedger.Event.ORDER_OCCUPY,"20260101000001",-1,1)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(jdbc);
    }
}
