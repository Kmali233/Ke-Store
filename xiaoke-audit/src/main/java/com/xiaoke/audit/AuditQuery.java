package com.xiaoke.audit;

import com.xiaoke.common.web.PageQuery;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.OffsetDateTime;

/**	接收查询条件，如操作者、对象、时间、页码*/
@Getter @Setter
public class AuditQuery extends PageQuery {
    @Positive private Long actorId;
    @Pattern(regexp="PRODUCT|SKU|CATEGORY|ORDER|ADMIN_ACCOUNT") private String targetType;
    @Pattern(regexp="[1-9][0-9]{0,18}") private String targetId;
    @Size(max=40) private String action;
    @Pattern(regexp="[a-f0-9]{32}") private String traceId;
    @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) private OffsetDateTime from;
    @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) private OffsetDateTime to;
}
