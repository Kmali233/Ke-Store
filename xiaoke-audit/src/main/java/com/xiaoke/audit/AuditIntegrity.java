package com.xiaoke.audit;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.LocalDateTime;
import java.util.HexFormat;

/** 计算审计记录的 SHA-256 摘要
 * AuditWriter 写入时调用，`AuditReader` 查询时再次调用 */
public final class AuditIntegrity {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private AuditIntegrity() { }
    public static String digest(String key,String actorType,Long actorId,String action,String targetType,String targetId,
                                String changes,String trace,String source,LocalDateTime time) {
        String canonical;
        try { canonical=JSON.writeValueAsString(JSON.readValue(changes,Object.class)); }
        catch(com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalArgumentException("Invalid audit JSON"); }
        String payload=String.join("|",key,actorType,actorId==null?"":actorId.toString(),action,targetType,targetId,
                "SUCCESS",canonical,trace,source,time.toString());
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
