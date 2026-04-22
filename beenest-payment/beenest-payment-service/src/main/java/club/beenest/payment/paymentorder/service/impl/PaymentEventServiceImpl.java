package club.beenest.payment.paymentorder.service.impl;

import club.beenest.payment.paymentorder.mapper.PaymentEventMapper;
import club.beenest.payment.paymentorder.dto.PaymentEventQueryDTO;
import club.beenest.payment.paymentorder.domain.entity.PaymentEvent;
import club.beenest.payment.paymentorder.domain.enums.PaymentEventStatus;
import club.beenest.payment.paymentorder.domain.enums.PaymentEventType;
import club.beenest.payment.paymentorder.service.IPaymentEventService;
import club.beenest.payment.paymentorder.service.IPaymentService;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 支付事件服务实现类
 * 负责支付事件日志的查询和补偿重放
 *
 * @author System
 * @since 2026-02-11
 */
@Service
public class PaymentEventServiceImpl implements IPaymentEventService {

    @Autowired
    private PaymentEventMapper paymentEventMapper;

    @Autowired
    private IPaymentService paymentService;

    /**
     * 查询支付事件列表
     * @param query 查询条件
     * @param pageNum 页码
     * @param pageSize 每页条数
     * @return 支付事件分页列表
     */
    @Override
    public Page<PaymentEvent> queryEvents(PaymentEventQueryDTO query, int pageNum, int pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        return (Page<PaymentEvent>) paymentEventMapper.selectByQuery(query.getOrderNo(), query.getEventType(), query.getChannel());
    }

    /**
     * 重放支付事件
     * @param id 事件ID
     */
    @Override
    public void replayEvent(Long id) {
        PaymentEvent event = paymentEventMapper.selectById(id);
        if (event == null) {
            return;
        }

        event.setStatusEnum(PaymentEventStatus.RETRY);
        event.setResponseContent("Replaying event");
        paymentEventMapper.update(event);

        try {
            if (event.getEventTypeEnum() != PaymentEventType.CALLBACK) {
                throw new IllegalArgumentException("仅支持重放支付回调事件");
            }
            if (!StringUtils.hasText(event.getOrderNo())) {
                throw new IllegalArgumentException("事件缺少订单号，无法重放");
            }

            paymentService.queryPaymentStatusForAdmin(event.getOrderNo());
            event.setStatusEnum(PaymentEventStatus.SUCCESS);
            event.setResponseContent("Replay reconciled payment status successfully");
        } catch (Exception e) {
            event.setStatusEnum(PaymentEventStatus.FAILED);
            event.setResponseContent(e.getMessage());
        }
        paymentEventMapper.update(event);
    }
}
