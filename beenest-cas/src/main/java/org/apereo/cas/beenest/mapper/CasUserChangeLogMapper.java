package org.apereo.cas.beenest.mapper;

import org.apereo.cas.beenest.entity.CasUserChangeLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户变更日志 Mapper
 */
@Mapper
public interface CasUserChangeLogMapper {

    void insert(CasUserChangeLog changeLog);

    /** 查询未同步的变更日志 */
    List<CasUserChangeLog> selectUnsynced(@Param("since") LocalDateTime since,
                                          @Param("limit") int limit);

    /** 批量标记已同步 */
    void markSynced(@Param("ids") List<Long> ids);
}
