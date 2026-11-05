package com.xiaoke.audit;

import com.fasterxml.jackson.databind.*;
import com.xiaoke.common.exception.BusinessException;
import jakarta.validation.Validator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;

/** 分页查询审计，重新校验摘要，组装结果.
 * AdminAuditController.page() 调用*/
public class AuditReader {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final Validator validator;
    public AuditReader(JdbcTemplate jdbc,ObjectMapper json,Clock clock,Validator validator) {
        this.jdbc=jdbc; this.json=json; this.clock=clock; this.validator=validator;
    }
    public record Entry(long id,String actorType,Long actorId,String action,String targetType,String targetId,
                        String result,JsonNode changes,String traceId,String source,LocalDateTime createdAt,boolean integrityValid) { }
    public record Page(long total,long pageNum,long pageSize,List<Entry> records) { }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page page(AuditQuery query) {
        if(query==null || !validator.validate(query).isEmpty()) throw invalid();
        query.validatePage();
        if(query.getPageNum()>1000) throw invalid();
        if(query.getAction()!=null) {
            try { AuditAction.valueOf(query.getAction()); } catch(IllegalArgumentException ex) { throw invalid(); }
        }
        LocalDateTime end=LocalDateTime.ofInstant(query.getTo()==null?clock.instant():query.getTo().toInstant(),ZoneOffset.UTC);
        LocalDateTime begin=query.getFrom()==null?end.minusDays(30):LocalDateTime.ofInstant(query.getFrom().toInstant(),ZoneOffset.UTC);
        if(!begin.isBefore(end) || Duration.between(begin,end).compareTo(Duration.ofDays(31))>0) throw invalid();
        StringBuilder where=new StringBuilder(" WHERE created_at>=? AND created_at<?");
        List<Object> args=new ArrayList<>(List.of(begin,end));
        add(where,args,"actor_id",query.getActorId()); add(where,args,"target_type",query.getTargetType());
        add(where,args,"target_id",query.getTargetId()); add(where,args,"action",query.getAction());
        add(where,args,"trace_id",query.getTraceId());
        long total=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM t_admin_audit"+where,Long.class,args.toArray()));
        args.add(query.getPageSize()); args.add((query.getPageNum()-1)*query.getPageSize());
        var entries=jdbc.query("SELECT * FROM t_admin_audit"+where+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",(rs,n)->{
            Long actor=rs.getObject("actor_id",Long.class);
            LocalDateTime time=rs.getObject("created_at",LocalDateTime.class);
            String payload=rs.getString("changes_json");
            String expected=AuditIntegrity.digest(rs.getString("event_key"),rs.getString("actor_type"),actor,rs.getString("action"),
                    rs.getString("target_type"),rs.getString("target_id"),payload,rs.getString("trace_id"),rs.getString("source"),time);
            boolean valid="SUCCESS".equals(rs.getString("result")) && expected.equals(rs.getString("payload_sha256"));
            JsonNode changes=null;
            if(valid) {
                try { changes=json.readTree(payload); } catch(com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("Invalid stored audit JSON"); }
            }
            return new Entry(rs.getLong("id"),rs.getString("actor_type"),actor,rs.getString("action"),rs.getString("target_type"),
                    rs.getString("target_id"),rs.getString("result"),changes,rs.getString("trace_id"),rs.getString("source"),time,valid);
        },args.toArray());
        return new Page(total,query.getPageNum(),query.getPageSize(),entries);
    }
    private void add(StringBuilder sql,List<Object> values,String column,Object value) {
        if(value!=null) { sql.append(" AND ").append(column).append("=?"); values.add(value); }
    }
    private static BusinessException invalid() { return new BusinessException(400,"审计查询参数不合法，时间跨度最多31天、页码最多1000"); }
}
