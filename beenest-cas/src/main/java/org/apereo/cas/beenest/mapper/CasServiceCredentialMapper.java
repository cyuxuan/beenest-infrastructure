package org.apereo.cas.beenest.mapper;

import org.apereo.cas.beenest.entity.CasServiceCredentialDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * CAS 服务凭证 Mapper。
 */
@Mapper
public interface CasServiceCredentialMapper {

    /**
     * 新增服务凭证记录。
     *
     * @param credential 服务凭证实体
     */
    void insert(CasServiceCredentialDO credential);

    /**
     * 按服务 ID 查询凭证记录。
     *
     * @param serviceId 服务 ID
     * @return 服务凭证记录
     */
    CasServiceCredentialDO selectByServiceId(@Param("serviceId") Long serviceId);

    /**
     * 更新服务凭证状态。
     *
     * @param serviceId 服务 ID
     * @param state 凭证状态
     */
    void updateStateByServiceId(@Param("serviceId") Long serviceId, @Param("state") String state);
}
