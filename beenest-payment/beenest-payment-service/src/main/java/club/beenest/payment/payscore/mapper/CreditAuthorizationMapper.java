package club.beenest.payment.payscore.mapper;

import club.beenest.payment.payscore.domain.entity.CreditAuthorization;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 信用授权记录 Mapper 接口
 * 对应数据库表：ds_credit_authorization
 *
 * @author System
 * @since 2026-06-15
 */
@Mapper
public interface CreditAuthorizationMapper {

    /**
     * 插入信用授权记录
     *
     * @param auth 信用授权记录
     * @return 影响行数
     */
    int insert(CreditAuthorization auth);

    /**
     * 根据授权编号查询
     *
     * @param authNo 授权编号
     * @return 信用授权记录
     */
    CreditAuthorization selectByAuthNo(@Param("authNo") String authNo);

    /**
     * 根据订单号查询
     *
     * @param orderNo 订单号
     * @return 信用授权记录
     */
    CreditAuthorization selectByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 更新信用授权记录
     *
     * @param auth 信用授权记录
     * @return 影响行数
     */
    int updateByAuthNo(CreditAuthorization auth);
}
