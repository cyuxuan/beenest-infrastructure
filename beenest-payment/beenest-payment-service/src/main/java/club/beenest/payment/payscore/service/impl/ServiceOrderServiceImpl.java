package club.beenest.payment.payscore.service.impl;

import club.beenest.payment.common.exception.BusinessException;
import club.beenest.payment.common.utils.AuthUtils;
import club.beenest.payment.common.utils.TradeNoGenerator;
import club.beenest.payment.payscore.domain.entity.CreditAuthorization;
import club.beenest.payment.payscore.domain.entity.ServiceOrder;
import club.beenest.payment.payscore.domain.enums.ServiceOrderStatus;
import club.beenest.payment.payscore.dto.CreditCheckResultDTO;
import club.beenest.payment.payscore.dto.ServiceOrderCreateDTO;
import club.beenest.payment.payscore.dto.ServiceOrderResultDTO;
import club.beenest.payment.payscore.mapper.CreditAuthorizationMapper;
import club.beenest.payment.payscore.mapper.ServiceOrderMapper;
import club.beenest.payment.payscore.service.IServiceOrderService;
import club.beenest.payment.payscore.strategy.PayScoreStrategy;
import club.beenest.payment.payscore.strategy.PayScoreStrategyFactory;
import club.beenest.payment.paymentorder.config.PaymentConfig;
import club.beenest.payment.shared.constant.BizTypeConstants;
import club.beenest.payment.shared.constant.PaymentConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 服务订单服务实现类
 * 实现支付分信用免押的核心业务逻辑
 *
 * <p>
 * 设计模式：
 * </p>
 * <ul>
 * <li>策略模式 - 根据支付分平台选择对应策略</li>
 * <li>工厂模式 - 通过工厂获取支付分策略</li>
 * <li>模板方法模式 - 策略内部使用模板方法定义流程骨架</li>
 * </ul>
 *
 * @author System
 * @since 2026-06-15
 */
@Service
@Slf4j
public class ServiceOrderServiceImpl implements IServiceOrderService {

    @Autowired
    private ServiceOrderMapper serviceOrderMapper;

    @Autowired
    private CreditAuthorizationMapper creditAuthorizationMapper;

    @Autowired
    private PayScoreStrategyFactory payScoreStrategyFactory;

    @Autowired
    private PaymentConfig paymentConfig;

    @Autowired
    private ObjectMapper objectMapper;

    // ==================== 信用免押检查 ====================

    @Override
    public CreditCheckResultDTO checkCreditEligibility(String customerNo, String platform, Long depositAmount) {
        log.info("信用免押检查 - customerNo: {}, platform: {}, depositAmount: {}", customerNo, platform, depositAmount);

        // 1. 参数校验
        if (!StringUtils.hasText(customerNo)) {
            throw new IllegalArgumentException("用户编号不能为空");
        }
        if (!StringUtils.hasText(platform)) {
            throw new IllegalArgumentException("支付分平台不能为空");
        }
        if (depositAmount == null || depositAmount <= 0) {
            throw new IllegalArgumentException("保证金金额必须大于0");
        }
        if (!payScoreStrategyFactory.isEnabled(platform)) {
            throw new IllegalArgumentException("支付分平台未启用: " + platform);
        }

        // 2. 获取策略并执行检查
        PayScoreStrategy strategy = payScoreStrategyFactory.getStrategy(platform);
        String serviceId = getServiceId(platform);

        return strategy.checkCreditEligibility(customerNo, serviceId, depositAmount);
    }

    // ==================== 创建服务订单 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ServiceOrderResultDTO createServiceOrder(String customerNo, ServiceOrderCreateDTO request) {
        log.info("创建服务订单 - customerNo: {}, platform: {}, depositAmount: {}, bizNo: {}",
                customerNo, request.getPlatform(), request.getDepositAmount(), request.getBizNo());

        try {
            // 1. 参数校验
            validateCreateRequest(customerNo, request);

            // 2. 检查是否存在进行中的服务订单（同一业务单号）
            if (StringUtils.hasText(request.getBizNo())) {
                ServiceOrder existing = serviceOrderMapper.selectLatestByBizNo(request.getBizNo());
                if (existing != null && !existing.isTerminal()) {
                    log.warn("该业务单号存在进行中的服务订单 - bizNo: {}, existingOrder: {}",
                            request.getBizNo(), existing.getOrderNo());
                    throw new BusinessException("该业务单号已存在进行中的信用免押订单，请勿重复创建");
                }
            }

            // 3. 生成订单号
            String orderNo = TradeNoGenerator.generateTransactionNo();

            // 4. 构建服务订单实体
            String serviceId = getServiceId(request.getPlatform());
            int expireMinutes = paymentConfig.getPayscore().getCommon().getAuthExpireMinutes();
            LocalDateTime now = LocalDateTime.now();

            ServiceOrder order = new ServiceOrder();
            order.setOrderNo(orderNo);
            order.setBizNo(request.getBizNo());
            order.setBizType(request.getBizType() != null ? request.getBizType() : PaymentConstants.BIZ_TYPE_MERCHANT_DEPOSIT);
            order.setAppId(BizTypeConstants.deriveAppId(request.getBizType() != null ? request.getBizType() : PaymentConstants.BIZ_TYPE_MERCHANT_DEPOSIT));
            order.setCustomerNo(customerNo);
            order.setPlatform(request.getPlatform());
            order.setServiceId(serviceId);
            order.setDepositAmount(request.getDepositAmount());
            order.setStatusEnum(ServiceOrderStatus.PENDING_AUTH);
            order.setExpireTime(now.plusMinutes(expireMinutes));
            order.setNotifyUrl(getNotifyUrl(request.getPlatform()));
            order.setRemark(request.getRemark());
            order.setExt(buildExtJson(request.getChannelUserId()));
            order.setCreateTime(now);
            order.setUpdateTime(now);

            // 5. 调用策略创建服务订单
            PayScoreStrategy strategy = payScoreStrategyFactory.getStrategy(request.getPlatform());
            Map<String, Object> authParams = strategy.createServiceOrder(order);

            // 6. 保存服务订单
            int insertResult = serviceOrderMapper.insert(order);
            if (insertResult != 1) {
                throw new BusinessException("保存服务订单失败");
            }

            // 7. 构建返回结果
            ServiceOrderResultDTO result = buildServiceOrderResult(order, authParams);

            log.info("创建服务订单完成 - orderNo: {}", orderNo);
            return result;

        } catch (IllegalArgumentException e) {
            log.warn("创建服务订单参数错误 - customerNo: {}, error: {}", customerNo, e.getMessage());
            throw e;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("创建服务订单失败 - customerNo: {}, error: {}", customerNo, e.getMessage(), e);
            throw new BusinessException("创建服务订单失败：" + e.getMessage(), e);
        }
    }

    // ==================== 处理授权回调 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean handleAuthCallback(String platform, HttpServletRequest request) {
        log.info("处理支付分授权回调 - platform: {}", platform);

        try {
            // 1. 解析回调数据
            Map<String, String> callbackData = parseCallbackData(request);

            // 2. 获取策略并验证签名
            PayScoreStrategy strategy = payScoreStrategyFactory.getStrategy(platform);
            if (!strategy.verifyAuthCallback(callbackData)) {
                log.error("授权回调签名验证失败 - platform: {}", platform);
                return false;
            }

            // 3. 解析回调数据
            Map<String, Object> parsedData = strategy.parseAuthCallback(callbackData);
            String orderNo = (String) parsedData.get("orderNo");
            Boolean authorized = (Boolean) parsedData.get("authorized");
            Long frozenAmount = parsedData.containsKey("frozenAmount") ? (Long) parsedData.get("frozenAmount") : null;
            String thirdPartyOrderNo = (String) parsedData.get("thirdPartyOrderNo");

            if (!StringUtils.hasText(orderNo)) {
                log.error("授权回调中未找到订单号 - platform: {}", platform);
                return false;
            }

            // 4. 加行锁查询服务订单
            ServiceOrder order = serviceOrderMapper.selectByOrderNoForUpdate(orderNo);
            if (order == null) {
                log.error("服务订单不存在 - orderNo: {}", orderNo);
                return false;
            }

            // 5. 幂等性检查
            if (order.isAuthorized() || order.isServiceActive()) {
                log.info("订单已授权，跳过处理 - orderNo: {}", orderNo);
                return true;
            }
            if (!order.isPendingAuth()) {
                log.warn("订单状态异常 - orderNo: {}, status: {}", orderNo, order.getStatus());
                return false;
            }

            // 6. 更新订单状态
            String callbackDataJson = objectMapper.writeValueAsString(callbackData);
            if (Boolean.TRUE.equals(authorized)) {
                order.markAsAuthorized(frozenAmount != null ? frozenAmount : order.getDepositAmount(), thirdPartyOrderNo);
            } else {
                order.markAsFailed();
            }
            order.setCallbackData(callbackDataJson);
            int updateResult = serviceOrderMapper.updateByOrderNo(order);
            if (updateResult != 1) {
                throw new BusinessException("更新服务订单状态失败");
            }

            log.info("授权回调处理成功 - orderNo: {}, authorized: {}", orderNo, authorized);
            return Boolean.TRUE.equals(authorized);

        } catch (Exception e) {
            log.error("处理授权回调失败 - platform: {}, error: {}", platform, e.getMessage(), e);
            throw new BusinessException("处理授权回调失败: " + e.getMessage(), e);
        }
    }

    // ==================== 完结服务订单 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ServiceOrderResultDTO completeServiceOrder(String orderNo, Long actualAmount) {
        log.info("完结服务订单 - orderNo: {}, actualAmount: {}", orderNo, actualAmount);

        try {
            // 1. 参数校验
            if (!StringUtils.hasText(orderNo)) {
                throw new IllegalArgumentException("订单号不能为空");
            }
            if (actualAmount == null || actualAmount < 0) {
                throw new IllegalArgumentException("实际扣款金额不能为负数");
            }

            // 2. 加行锁查询服务订单
            ServiceOrder order = serviceOrderMapper.selectByOrderNoForUpdate(orderNo);
            if (order == null) {
                throw new BusinessException("服务订单不存在");
            }

            // 3. 校验订单状态（已授权或服务中才能完结）
            if (!order.isAuthorized() && !order.isServiceActive()) {
                throw new BusinessException("订单状态不允许完结，当前状态: " + order.getStatusDisplayName());
            }

            // 4. 金额校验：实际扣款不能超过冻结金额
            if (order.getFrozenAmount() != null && actualAmount > order.getFrozenAmount()) {
                throw new BusinessException("实际扣款金额不能超过冻结金额");
            }

            // 5. 更新订单状态为完结中
            order.markAsCompleting(actualAmount);
            int updateResult = serviceOrderMapper.updateByOrderNo(order);
            if (updateResult != 1) {
                throw new BusinessException("更新服务订单状态失败");
            }

            // 6. 调用策略完结服务订单
            PayScoreStrategy strategy = payScoreStrategyFactory.getStrategy(order.getPlatform());
            Map<String, Object> completeResult = strategy.completeServiceOrder(order, actualAmount);

            // 7. 完结回调确认状态，此处不立即标记为COMPLETED（等待完结回调确认）
            // 如果策略返回完结成功，则直接更新为COMPLETED
            boolean success = Boolean.TRUE.equals(completeResult.get("success"));
            if (success) {
                order.markAsCompleted(objectMapper.writeValueAsString(completeResult));
  serviceOrderMapper.updateByOrderNo(order);
            }

            ServiceOrderResultDTO result = buildServiceOrderResult(order, null);
            log.info("完结服务订单完成 - orderNo: {}, actualAmount: {}", orderNo, actualAmount);
            return result;

        } catch (IllegalArgumentException e) {
            log.warn("完结服务订单参数错误 - orderNo: {}, error: {}", orderNo, e.getMessage());
            throw e;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("完结服务订单失败 - orderNo: {}, error: {}", orderNo, e.getMessage(), e);
            throw new BusinessException("完结服务订单失败：" + e.getMessage(), e);
        }
    }

    // ==================== 处理完结回调 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean handleCompleteCallback(String platform, HttpServletRequest request) {
        log.info("处理支付分完结回调 - platform: {}", platform);

        try {
            // 1. 解析回调数据
            Map<String, String> callbackData = parseCallbackData(request);

            // 2. 验证签名
            PayScoreStrategy strategy = payScoreStrategyFactory.getStrategy(platform);
            if (!strategy.verifyCompleteCallback(callbackData)) {
                // 签名失败但返回true，避免微信/支付宝无限重试
                log.error("【安全告警】完结回调签名验证失败 - platform: {}", platform);
                return true;
            }

            // 3. 解析回调数据
            Map<String, Object> parsedData = strategy.parseCompleteCallback(callbackData);
            String orderNo = (String) parsedData.get("orderNo");
            Boolean completed = (Boolean) parsedData.get("completed");

            if (!StringUtils.hasText(orderNo)) {
                log.error("完结回调中未找到订单号 - platform: {}", platform);
                return false;
            }

            // 4. 加行锁查询服务订单
            ServiceOrder order = serviceOrderMapper.selectByOrderNoForUpdate(orderNo);
            if (order == null) {
                log.error("服务订单不存在 - orderNo: {}", orderNo);
                return false;
            }

            // 5. 幂等性检查
            if (order.isCompleted()) {
                log.info("订单已完结，跳过处理 - orderNo: {}", orderNo);
                return true;
            }

            // 6. 更新订单状态
            if (Boolean.TRUE.equals(completed)) {
                String callbackDataJson = objectMapper.writeValueAsString(callbackData);
                order.markAsCompleted(callbackDataJson);
                serviceOrderMapper.updateByOrderNo(order);
            }

            log.info("完结回调处理成功 - orderNo: {}", orderNo);
            return true;

        } catch (Exception e) {
            log.error("处理完结回调失败 - platform: {}, error: {}", platform, e.getMessage(), e);
            throw new BusinessException("处理完结回调失败: " + e.getMessage(), e);
        }
    }

    // ==================== 取消服务订单 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelServiceOrder(String orderNo, String reason) {
        log.info("取消服务订单 - orderNo: {}, reason: {}", orderNo, reason);

        try {
            // 1. 参数校验
            if (!StringUtils.hasText(orderNo)) {
                throw new IllegalArgumentException("订单号不能为空");
            }

            // 2. 加行锁查询服务订单
            ServiceOrder order = serviceOrderMapper.selectByOrderNoForUpdate(orderNo);
            if (order == null) {
                throw new BusinessException("服务订单不存在");
            }

            // 3. 校验订单状态
            if (order.isTerminal()) {
                log.warn("订单已为终态，无法取消 - orderNo: {}, status: {}", orderNo, order.getStatus());
                return false;
            }

            // 4. 调用策略取消服务订单
            PayScoreStrategy strategy = payScoreStrategyFactory.getStrategy(order.getPlatform());
            Map<String, Object> cancelResult = strategy.cancelServiceOrder(order, reason);
            boolean success = Boolean.TRUE.equals(cancelResult.get("success"));

            if (!success) {
                log.warn("第三方取消服务订单失败 - orderNo: {}", orderNo);
            }

            // 5. 更新本地状态为已取消
            order.markAsCancelled();
            order.setCallbackData(objectMapper.writeValueAsString(cancelResult));
            int updateResult = serviceOrderMapper.updateByOrderNo(order);
            if (updateResult != 1) {
                throw new BusinessException("更新服务订单状态失败");
            }

            log.info("取消服务订单完成 - orderNo: {}", orderNo);
            return true;

        } catch (IllegalArgumentException e) {
            log.warn("取消服务订单参数错误 - orderNo: {}, error: {}", orderNo, e.getMessage());
            throw e;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("取消服务订单失败 - orderNo: {}, error: {}", orderNo, e.getMessage(), e);
            throw new BusinessException("取消服务订单失败：" + e.getMessage(), e);
        }
    }

    // ==================== 查询服务订单 ====================

    @Override
    public ServiceOrderResultDTO queryServiceOrderStatus(String customerNo, String orderNo) {
        log.info("查询服务订单状态 - orderNo: {}", orderNo);

        try {
            if (!StringUtils.hasText(orderNo)) {
                throw new IllegalArgumentException("订单号不能为空");
            }

            ServiceOrder order = serviceOrderMapper.selectByOrderNo(orderNo);
            if (order == null) {
                throw new IllegalArgumentException("订单不存在");
            }
            if (StringUtils.hasText(customerNo) && !customerNo.equals(order.getCustomerNo())) {
                throw new IllegalArgumentException("无权查看该订单");
            }

            return buildServiceOrderResult(order, null);

        } catch (IllegalArgumentException e) {
            log.warn("查询服务订单状态参数错误 - orderNo: {}, error: {}", orderNo, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("查询服务订单状态失败 - orderNo: {}, error: {}", orderNo, e.getMessage(), e);
            throw new BusinessException("查询服务订单状态失败：" + e.getMessage(), e);
        }
    }

    @Override
    public ServiceOrderResultDTO getLatestServiceOrderByBizNo(String bizNo) {
        log.info("根据业务单号查询最新服务订单 - bizNo: {}", bizNo);
        if (!StringUtils.hasText(bizNo)) {
            throw new IllegalArgumentException("业务单号不能为空");
        }
        ServiceOrder order = serviceOrderMapper.selectLatestByBizNo(bizNo);
        if (order == null) {
            return null;
        }
        return buildServiceOrderResult(order, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ServiceOrderResultDTO modifyServiceOrderAmount(String orderNo, Long newAmount) {
        log.info("修改服务订单冻结金额 - orderNo: {}, newAmount: {}", orderNo, newAmount);

        try {
            if (!StringUtils.hasText(orderNo)) {
                throw new IllegalArgumentException("订单号不能为空");
            }
            if (newAmount == null || newAmount <= 0) {
                throw new IllegalArgumentException("冻结金额必须大于0");
            }

            ServiceOrder order = serviceOrderMapper.selectByOrderNoForUpdate(orderNo);
            if (order == null) {
                throw new BusinessException("服务订单不存在");
            }
            if (!order.isServiceActive() && !order.isAuthorized()) {
                throw new BusinessException("订单状态不允许修改金额");
            }

            PayScoreStrategy strategy = payScoreStrategyFactory.getStrategy(order.getPlatform());
            Map<String, Object> modifyResult = strategy.modifyServiceOrder(order, newAmount);

            order.setFrozenAmount(newAmount);
            serviceOrderMapper.updateByOrderNo(order);

            return buildServiceOrderResult(order, modifyResult);

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("修改服务订单金额失败 - orderNo: {}, error: {}", orderNo, e.getMessage(), e);
            throw new BusinessException("修改服务订单金额失败：" + e.getMessage(), e);
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 验证创建服务订单请求参数
     */
    private void validateCreateRequest(String customerNo, ServiceOrderCreateDTO request) {
        if (!StringUtils.hasText(customerNo)) {
            throw new IllegalArgumentException("用户编号不能为空");
        }
        if (!StringUtils.hasText(request.getPlatform())) {
            throw new IllegalArgumentException("支付分平台不能为空");
        }
        if (!payScoreStrategyFactory.isEnabled(request.getPlatform())) {
            throw new IllegalArgumentException("支付分平台未启用: " + request.getPlatform());
        }
        if (request.getDepositAmount() == null || request.getDepositAmount() <= 0) {
            throw new IllegalArgumentException("保证金金额必须大于0");
        }
    }

    /**
     * 获取服务ID
     */
    private String getServiceId(String platform) {
        PaymentConfig.PayScoreConfig payscore = paymentConfig.getPayscore();
        return switch (platform.toUpperCase()) {
            case PaymentConstants.PLATFORM_WECHAT_PAYSCORE -> payscore.getWechat().getServiceId();
            case PaymentConstants.PLATFORM_ALIPAY_ZHIMA -> payscore.getAlipay().getProductCode();
            default -> throw new IllegalArgumentException("不支持的支付分平台: " + platform);
        };
    }

    /**
     * 获取回调通知地址
     */
    private String getNotifyUrl(String platform) {
        PaymentConfig.PayScoreConfig payscore = paymentConfig.getPayscore();
        return switch (platform.toUpperCase()) {
            case PaymentConstants.PLATFORM_WECHAT_PAYSCORE -> payscore.getWechat().getNotifyUrl();
            case PaymentConstants.PLATFORM_ALIPAY_ZHIMA -> payscore.getAlipay().getNotifyUrl();
            default -> null;
        };
    }

    /**
     * 构建扩展字段JSON
     */
    private String buildExtJson(String channelUserId) {
        if (!StringUtils.hasText(channelUserId)) {
            return null;
        }
        try {
            var ext = objectMapper.createObjectNode();
            ext.put("channelUserId", channelUserId);
            return ext.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 构建服务订单返回结果
     */
    private ServiceOrderResultDTO buildServiceOrderResult(ServiceOrder order, Map<String, Object> authParams) {
        return new ServiceOrderResultDTO()
                .setOrderNo(order.getOrderNo())
                .setBizNo(order.getBizNo())
                .setPlatform(order.getPlatform())
                .setPlatformName(order.getPlatformDisplayName())
                .setStatus(order.getStatus())
                .setStatusDisplayName(order.getStatusDisplayName())
                .setDepositAmount(order.getDepositAmount())
                .setFrozenAmount(order.getFrozenAmount())
                .setActualAmount(order.getActualAmount())
                .setAuthParams(authParams)
                .setExpireTime(order.getExpireTime())
                .setAuthTime(order.getAuthTime())
                .setCompleteTime(order.getCompleteTime());
    }

    /**
     * 解析HTTP请求数据为Map（复用 PaymentServiceImpl 中的逻辑）
     */
    private Map<String, String> parseCallbackData(HttpServletRequest request) throws Exception {
        Map<String, String> data = new HashMap<>();

        // 白名单头部
        List<String> whitelistHeaders = List.of(
                "Wechatpay-Signature", "Wechatpay-Timestamp", "Wechatpay-Nonce",
                "Wechatpay-Serial", "Wechatpay-Certificate",
                "Alipay-Signature", "Alipay-Timestamp", "Alipay-App-Id",
                "Alipay-Charset", "Alipay-Sign-Type", "Alipay-Notify-Type");
        for (String header : whitelistHeaders) {
            String value = request.getHeader(header);
            if (value != null) {
                data.put(header, value);
            }
        }

        // 请求参数
        request.getParameterMap().forEach((key, values) -> {
            if (values != null && values.length > 0) {
                data.put(key, values[0]);
            }
        });

        // POST请求体
        if ("POST".equalsIgnoreCase(request.getMethod())) {
            try (var reader = request.getReader()) {
                String body = reader.lines().collect(Collectors.joining());
                if (StringUtils.hasText(body)) {
                    data.put("body", body);
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> jsonData = objectMapper.readValue(body, Map.class);
                        jsonData.forEach((key, value) -> {
                            if (value != null) {
                                data.put(key, value.toString());
                            }
                        });
                    } catch (Exception e) {
                        // 非JSON格式忽略
                    }
                }
            }
        }

        return data;
    }
}
