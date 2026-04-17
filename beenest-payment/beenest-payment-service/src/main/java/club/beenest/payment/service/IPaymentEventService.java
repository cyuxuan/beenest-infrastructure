package club.beenest.payment.service;

import club.beenest.payment.object.dto.PaymentEventQueryDTO;
import club.beenest.payment.object.entity.PaymentEvent;
import com.github.pagehelper.Page;

/**
 * 支付事件服务接口
 * 提供支付事件日志的查询和重放功能
 *
 * <p>支付事件记录了每一笔支付回调的完整生命周期（PENDING → SUCCESS/FAILED），
 * 用于问题排查、事件回溯和补偿重放。</p>
 *
 * @author System
 * @since 2026-02-11
 */
public interface IPaymentEventService {

    /**
     * 分页查询支付事件
     *
     * @param query    查询条件（订单号、事件类型、渠道等）
     * @param pageNum  页码，从1开始
     * @param pageSize 每页大小
     * @return 支付事件分页列表
     */
    Page<PaymentEvent> queryEvents(PaymentEventQueryDTO query, int pageNum, int pageSize);

    /**
     * 重放支付事件
     *
     * <p>重新触发指定支付事件的状态同步，适用于回调丢失或处理失败的补偿场景。</p>
     *
     * @param id 事件ID
     * @throws IllegalArgumentException 如果事件不存在或事件类型不支持重放
     */
    void replayEvent(Long id);
}
