package org.apereo.cas.beenest.mapper;

import org.apereo.cas.beenest.entity.CasAuthAuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 认证审计日志 Mapper
 */
@Mapper
public interface CasAuthAuditLogMapper {

    void insert(CasAuthAuditLog log);
}
