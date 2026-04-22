package club.beenest.payment.wallet.mapper;

import club.beenest.payment.wallet.domain.entity.WalletIntegrityLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WalletIntegrityLogMapper {

    int insert(WalletIntegrityLog log);

    int countUnresolvedByWalletNo(@Param("walletNo") String walletNo);
}
