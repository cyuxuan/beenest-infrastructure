package org.apereo.cas.beenest.mapper;

import org.apereo.cas.beenest.entity.CasSyncStrategy;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 同步策略 Mapper
 */
@Mapper
public interface CasSyncStrategyMapper {

    CasSyncStrategy selectByServiceId(@Param("serviceId") Long serviceId);

    List<CasSyncStrategy> selectPushEnabled();

    void insert(CasSyncStrategy strategy);

    void updateByServiceId(CasSyncStrategy strategy);
}
