package com.xiaoke.order.reconciliation;

import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

public interface ReconciliationMapper {
    enum Kind { PAYMENT, REFUND }
    enum Outcome {
        MATCHED, REPAIRED, PROCESSING, NOT_FOUND, STATE_MISMATCH, AMOUNT_MISMATCH,
        IDENTITY_MISMATCH, DUPLICATE_TRADE, QUERY_FAILED, CHANNEL_FAILED, APPLY_FAILED
    }
    record Candidate(long orderId, String orderNo, Kind kind) {}
    record Entry(String orderNo, Kind kind, Outcome outcome, int attempts, LocalDateTime checkedAt) {}

    String CANDIDATES = """
        SELECT o.id AS order_id,o.order_no,'PAYMENT' AS kind
        FROM t_payment_order p JOIN t_orders o ON o.id=p.order_id AND o.deleted=0
        UNION ALL
        SELECT o.id AS order_id,o.order_no,'REFUND' AS kind
        FROM t_order_refund r JOIN t_orders o ON o.id=r.order_id AND o.deleted=0
        """;

    @Select("""
        SELECT c.order_id,c.order_no,c.kind FROM (
        """ + CANDIDATES + """
        ) c LEFT JOIN t_trade_reconciliation t ON t.order_id=c.order_id AND t.kind=c.kind
        ORDER BY t.checked_at,c.order_id,c.kind LIMIT 3
        """)
    List<Candidate> nextBatch();

    @Select("""
        SELECT c.order_id,c.order_no,c.kind FROM (
        """ + CANDIDATES + """
        ) c WHERE c.order_no=#{no} AND c.kind=#{kind}
        """)
    Candidate find(@Param("no") String orderNo, @Param("kind") Kind kind);

    @Select("""
        <script>
        SELECT o.order_no,t.kind,t.outcome,t.attempts,t.checked_at
        FROM t_trade_reconciliation t JOIN t_orders o ON o.id=t.order_id AND o.deleted=0
        <where>
          <if test="no != null">o.order_no=#{no}</if>
          <if test="differences">AND t.outcome NOT IN ('MATCHED','REPAIRED')</if>
        </where>
        ORDER BY t.checked_at DESC,t.order_id DESC,t.kind LIMIT 50 OFFSET #{offset}
        </script>
        """)
    List<Entry> list(@Param("no") String orderNo, @Param("differences") boolean differences,
                     @Param("offset") long offset);

    @Select("""
        SELECT o.order_no,t.kind,t.outcome,t.attempts,t.checked_at
        FROM t_trade_reconciliation t JOIN t_orders o ON o.id=t.order_id
        WHERE t.order_id=#{id} AND t.kind=#{kind}
        """)
    Entry entry(@Param("id") long orderId, @Param("kind") Kind kind);

    @Insert("""
        INSERT INTO t_trade_reconciliation(order_id,kind,outcome,attempts,checked_at)
        VALUES (#{id},#{kind},#{outcome},1,UTC_TIMESTAMP(6))
        ON DUPLICATE KEY UPDATE outcome=#{outcome},attempts=attempts+1,checked_at=UTC_TIMESTAMP(6)
        """)
    int save(@Param("id") long orderId, @Param("kind") Kind kind, @Param("outcome") Outcome outcome);
}
