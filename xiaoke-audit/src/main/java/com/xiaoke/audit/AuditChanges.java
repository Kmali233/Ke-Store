package com.xiaoke.audit;

import java.math.BigDecimal;
import java.util.*;

/** 整理允许记录的前后差异
 * 业务 Service 在调用 record(...) 前构造 */
public final class AuditChanges {
    /** 允许写入审核负载的字段白名单，枚举名即负载中的 key。 */
    public enum Field {
        PRICE,  // 价格
        STATUS,  // 状态
        STOCK,  // 库存
        DELETED,  // 删除
        ROLE_IDS,  // 角色ID
        PARENT_ID,  // 父ID
        CATEGORY_ID,  // 类别ID
        PROFILE_CHANGED,  // 资料改变
        ATTR_COUNT,  // 属性数量
        SESSION_REVOKED  // 会话撤销
    }
    /** 单字段差异：before 为归一化后的旧值，after 为新值。 */
    public record Change(Object before, Object after) { }
    /** 按字段名排序存储，保证不同调用顺序产生的负载字节一致。 */
    private final Map<String,Change> values=new TreeMap<>();
    /** 创建一个空的变更集。 */
    public static AuditChanges start() { return new AuditChanges(); }
    /** 登记一次字段变更：先归一化，新旧值相同则忽略，避免写入无实际变化的审计记录。 */
    public AuditChanges put(Field field,Object before,Object after) {
        Object left=normalize(field,before),right=normalize(field,after);
        if (!Objects.equals(left,right))
            values.put(field.name(),new Change(left,right));
        return this;
    }
    /** 仅在资料确有变化时记录 PROFILE_CHANGED，避免敏感字段本身入负载。 */
    public AuditChanges profile(boolean changed) {
        return changed?put(Field.PROFILE_CHANGED,false,true):this;
    }
    /** 返回只读视图，防止调用方在写入审计前篡改内容。 */
    public Map<String,Change> values() {
        return Collections.unmodifiableMap(values);
    }
    /** 无任何有效变更时返回 true，调用方据此跳过审计写入。 */
    public boolean empty() {
        return values.isEmpty();
    }
    /** 按字段类型把入参收窄为可安全序列化的标量，不合法值直接抛异常，绝不透传原始对象。 */
    private Object normalize(Field field,Object value) {
        if(value==null) return null;
        if(field==Field.PRICE && value instanceof BigDecimal price && price.signum()>=0 && price.scale()<=2
                && price.compareTo(new BigDecimal("99999999.99"))<=0) return price.stripTrailingZeros().toPlainString();
        if((field==Field.PROFILE_CHANGED || field==Field.SESSION_REVOKED) && value instanceof Boolean) return value;
        if(field==Field.ROLE_IDS && value instanceof Collection<?> items && items.size()<=4) {
            var ids=new TreeSet<Long>();
            for(Object item:items) {
                if(!(item instanceof Long || item instanceof Integer) || ((Number)item).longValue()<=0) throw invalid();
                ids.add(((Number)item).longValue());
            }
            return List.copyOf(ids);
        }
        if(field!=Field.PRICE && field!=Field.PROFILE_CHANGED && field!=Field.SESSION_REVOKED && field!=Field.ROLE_IDS
                && (value instanceof Integer || value instanceof Long) && ((Number)value).longValue()>=0)
            return ((Number)value).longValue();
        throw invalid();
    }
    /** 统一的不合法负载出口，异常信息不含原值，避免敏感数据进入日志。 */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Audit payload requires whitelisted scalar values"); }
}
