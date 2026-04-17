package org.apereo.cas.beenest.mapper;

import org.apereo.cas.beenest.entity.CasAppAccess;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 应用访问控制 Mapper
 */
@Mapper
public interface CasAppAccessMapper {

    /** 检查用户是否有权访问某应用 */
    CasAppAccess selectByUserAndService(@Param("userId") String userId, @Param("serviceId") Long serviceId);

    /** 授予用户访问权限 */
    void insert(CasAppAccess access);

    /** 撤销用户访问权限 */
    void deleteByUserAndService(@Param("userId") String userId, @Param("serviceId") Long serviceId);

    /** 查询用户可访问的所有应用 */
    List<CasAppAccess> selectByUserId(@Param("userId") String userId);

    /** 查询某应用的所有用户 */
    List<CasAppAccess> selectByServiceId(@Param("serviceId") Long serviceId);
}
