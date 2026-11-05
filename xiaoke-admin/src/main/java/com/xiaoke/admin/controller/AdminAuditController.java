package com.xiaoke.admin.controller;

import com.xiaoke.audit.*;
import com.xiaoke.common.auth.admin.*;
import com.xiaoke.common.result.Result;
import com.xiaoke.common.web.AllowedQuery;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/audit")
public class AdminAuditController {
    private final AuditReader audit;
    public AdminAuditController(AuditReader audit) { this.audit=audit; }
    @GetMapping("/page") @AdminPermission(AdminPermissions.AUDIT_READ)
    @AllowedQuery({"actorId","targetType","targetId","action","traceId","from","to","pageNum","pageSize"})
    public Result<AuditReader.Page> page(@Valid AuditQuery query) { return Result.success(audit.page(query)); }
}
