package com.xiaoke.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaoke.common.auth.admin.AdminContext;
import org.slf4j.MDC;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** 补齐身份、时间、traceId 等信息，并插入审计表
 * 商品、订单、管理员等业务 Service 直接调用 */
public class AuditWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final String source;
    public AuditWriter(JdbcTemplate jdbc,ObjectMapper json,Clock clock,Environment env) {
        this.jdbc=jdbc; this.json=json; this.clock=clock;
        source=env.getProperty("spring.application.name","xiaoke-audit");
        if(!source.matches("[a-z][a-z0-9-]{0,49}")) throw new IllegalStateException("Invalid audit source");
    }
    /** audit.record(动作, 对象ID, 变更清单); 最后负责插入审计记录到数据库*/
    public void record(AuditAction action,long target,AuditChanges changes) { record(action,Long.toString(target),changes); }
    public void record(AuditAction action,String target,AuditChanges changes) {
        if(!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("Success audit requires the owning writable business transaction");
        if(action==null || target==null || !target.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("Invalid audit target");
        if(changes.empty()) return;
        var actor=AdminContext.current();
        String actorType=actor.isPresent()?"ADMIN":"SYSTEM";
        Long actorId=actor.map(p->p.adminId()).orElse(null);
        String trace=MDC.get("traceId");
        if(trace==null) trace=UUID.randomUUID().toString().replace("-","");
        if(!trace.matches("[a-f0-9]{32}")) throw new IllegalStateException("Invalid server audit trace");
        String key=action==AuditAction.ORDER_SHIP?"ORDER_SHIP:"+target:trace+":"+action.name()+":"+target;
        String payload;
        try { payload=json.writeValueAsString(changes.values()); }
        catch(JsonProcessingException ex) { throw new IllegalStateException("Audit serialization failed"); }
        LocalDateTime time=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        String hash=AuditIntegrity.digest(key,actorType,actorId,action.name(),action.target(),target,payload,trace,source,time);
        jdbc.update("""
                INSERT INTO t_admin_audit(event_key,actor_type,actor_id,action,target_type,target_id,changes_json,trace_id,source,created_at,payload_sha256)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """,key,actorType,actorId,action.name(),action.target(),target,payload,trace,source,time,hash);
    }
}
