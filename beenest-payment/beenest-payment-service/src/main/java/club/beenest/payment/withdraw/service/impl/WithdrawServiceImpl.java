package club.beenest.payment.withdraw.service.impl;

import club.beenest.payment.common.annotation.LogAudit;
import club.beenest.payment.common.annotation.RateLimiter;
import club.beenest.payment.common.exception.BusinessException;
import club.beenest.payment.common.exception.RiskControlException;
import club.beenest.payment.withdraw.config.WithdrawConfig;
import club.beenest.payment.shared.constant.BizTypeConstants;
import club.beenest.payment.shared.constant.PaymentConstants;
import club.beenest.payment.wallet.service.IWalletService;
import club.beenest.payment.withdraw.mapper.WithdrawRequestMapper;
import club.beenest.payment.withdraw.mq.WithdrawCompletedMessage;
import club.beenest.payment.paymentorder.mq.producer.PaymentEventProducer;
import club.beenest.payment.withdraw.dto.WithdrawAuditDTO;
import club.beenest.payment.withdraw.dto.WithdrawRequestDTO;
import club.beenest.payment.withdraw.dto.WithdrawRequestQueryDTO;
import club.beenest.payment.withdraw.dto.WithdrawResultDTO;
import club.beenest.payment.wallet.domain.entity.Wallet;
import club.beenest.payment.withdraw.domain.entity.WithdrawRequest;
import club.beenest.payment.withdraw.domain.enums.WithdrawStatus;
import club.beenest.payment.withdraw.service.IWithdrawService;
import club.beenest.payment.withdraw.service.WithdrawLimitChecker;
import club.beenest.payment.withdraw.service.WithdrawRiskChecker;
import club.beenest.payment.withdraw.strategy.WithdrawStrategy;
import club.beenest.payment.withdraw.strategy.WithdrawStrategyFactory;
import club.beenest.payment.util.PaymentValidateUtils;
import club.beenest.payment.common.utils.TradeNoGenerator;
import club.beenest.payment.common.utils.TransactionSynchronizationUtils;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 提现服务实现类（重构版）
 * 使用策略模式和工厂模式实现提现功能
 * 
 * <p>设计模式：</p>
 * <ul>
 *   <li>策略模式 - 每个提现平台作为独立策略</li>
 *   <li>工厂模式 - 通过工厂获取提现策略</li>
 *   <li>模板方法模式 - 定义提现流程骨架</li>
 * </ul>
 * 
 * @author System
 * @since 2026-01-27
 */
@Service
@Slf4j
public class WithdrawServiceImpl implements IWithdrawService {
    
    @Autowired
    private WithdrawRequestMapper withdrawRequestMapper;
    
    @Autowired
    private IWalletService walletService;
    
    @Autowired
    private WithdrawStrategyFactory withdrawStrategyFactory;
    
    @Autowired(required = false)
    private WithdrawConfig withdrawConfig;

    @Autowired(required = false)
    private WithdrawLimitChecker limitChecker;
    
    @Autowired(required = false)
    private WithdrawRiskChecker riskChecker;

    @Autowired
    private PaymentEventProducer paymentEventProducer;
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogAudit(module = "提现管理", operation = "创建提现申请")
    @RateLimiter(key = "withdraw:#{customerNo}", limit = 3, period = 86400)  // 每天3次
    public WithdrawResultDTO createWithdrawRequest(String customerNo, WithdrawRequestDTO withdrawRequestDTO) {
        log.info("创建提现申请 - customerNo: {}, amount: {}, platform: {}", 
                customerNo, withdrawRequestDTO.getAmount(), withdrawRequestDTO.getWithdrawType());
        
        boolean reservedLimit = false;
        try {
            // 1. 验证参数
            validateWithdrawRequest(customerNo, withdrawRequestDTO);
            
            // 2. 获取提现策略
            WithdrawStrategy strategy = withdrawStrategyFactory.getStrategy(withdrawRequestDTO.getWithdrawType());
            
            // 3. 风控检查
            boolean needManualReview = false;
            if (riskChecker != null) {
                WithdrawRiskChecker.RiskCheckResult riskResult = riskChecker.check(customerNo, withdrawRequestDTO);
                
                if (!riskResult.isPass()) {
                    log.warn("风控检查未通过 - customerNo: {}, reason: {}", customerNo, riskResult.getMessage());
                    throw new RiskControlException(riskResult.getMessage());
                }
                
                if (riskResult.isNeedManualReview()) {
                    log.info("需要人工审核 - customerNo: {}, reason: {}", customerNo, riskResult.getMessage());
                    needManualReview = true;
                    if (withdrawRequestDTO.getRemark() == null || withdrawRequestDTO.getRemark().isEmpty()) {
                        withdrawRequestDTO.setRemark(riskResult.getMessage());
                    } else {
                        withdrawRequestDTO.setRemark(withdrawRequestDTO.getRemark() + "；" + riskResult.getMessage());
                    }
                }
            }
            
            // 4. 检查额度并预留
            if (limitChecker != null) {
                String platform = withdrawRequestDTO.getWithdrawType();
                Long dailyTotalLimit = getDailyTotalLimit(platform);
                Long dailyUserLimit = getDailyUserLimit(platform);
                WithdrawLimitChecker.ReserveCode reserveCode = limitChecker.reserveDailyAmount(
                        platform,
                        customerNo,
                        withdrawRequestDTO.getAmount(),
                        dailyTotalLimit,
                        dailyUserLimit
                );

                if (reserveCode == WithdrawLimitChecker.ReserveCode.DAILY_TOTAL_EXCEEDED) {
                    throw new BusinessException("超过单日总提现额度，请明天再试");
                }
                if (reserveCode == WithdrawLimitChecker.ReserveCode.DAILY_USER_EXCEEDED) {
                    throw new BusinessException("超过单日个人提现额度，请明天再试");
                }
                reservedLimit = true;
            }
            
            // 5. 验证账户信息
            if (!strategy.validateAccount(withdrawRequestDTO.getAccountNumber(), withdrawRequestDTO.getAccountName())) {
                throw new IllegalArgumentException("账户信息验证失败");
            }
            
            // 6. 查询用户钱包（多租户隔离）
            Wallet wallet = walletService.getWallet(customerNo, BizTypeConstants.DEFAULT);
            if (wallet == null) {
                throw new BusinessException("用户钱包不存在");
            }

            // 7. 计算手续费和实际到账金额
            Long feeAmount = strategy.calculateFee(withdrawRequestDTO.getAmount());
            Long actualAmount = withdrawRequestDTO.getAmount() - feeAmount;

            // 8. 检查余额是否充足
            if (wallet.getBalance() < withdrawRequestDTO.getAmount()) {
                throw new BusinessException("余额不足");
            }

            // 9. 检查是否有处理中的申请
            int processingCount = withdrawRequestMapper.countProcessingByCustomerNo(customerNo);
            if (processingCount > 0) {
                throw new BusinessException("您有正在处理中的提现申请，请等待处理完成");
            }

            // 10. 生成申请号
            String requestNo = TradeNoGenerator.generateWithdrawRequestNo();

            // 11. 冻结余额
            boolean freezeResult = walletService.freezeBalance(customerNo, BizTypeConstants.DEFAULT,
                    withdrawRequestDTO.getAmount(), "提现冻结", requestNo);
            if (!freezeResult) {
                throw new BusinessException("冻结余额失败");
            }
            
            // 12. 创建提现申请记录
            WithdrawRequest withdrawRequest = new WithdrawRequest();
            withdrawRequest.setRequestNo(requestNo);
            withdrawRequest.setCustomerNo(customerNo);
            withdrawRequest.setWalletNo(wallet.getWalletNo());
            withdrawRequest.setAmount(withdrawRequestDTO.getAmount());
            withdrawRequest.setAccountType(withdrawRequestDTO.getAccountType());
            withdrawRequest.setFeeAmount(feeAmount);
            withdrawRequest.setActualAmount(actualAmount);
            withdrawRequest.setWithdrawType(withdrawRequestDTO.getWithdrawType());  // 使用withdrawType字段
            withdrawRequest.setAccountNumber(withdrawRequestDTO.getAccountNumber());  // 使用accountNumber字段
            withdrawRequest.setAccountName(withdrawRequestDTO.getAccountName());
            withdrawRequest.setBankName(withdrawRequestDTO.getBankName());
            withdrawRequest.setBankBranch(withdrawRequestDTO.getBankBranch());
            withdrawRequest.setStatusEnum(needManualReview ? WithdrawStatus.MANUAL_REVIEW : WithdrawStatus.PENDING);
            withdrawRequest.setBizType(BizTypeConstants.DEFAULT);
            withdrawRequest.setRemark(withdrawRequestDTO.getRemark());
            withdrawRequest.setCreateTime(LocalDateTime.now());
            withdrawRequest.setUpdateTime(LocalDateTime.now());
            
            withdrawRequestMapper.insert(withdrawRequest);

            // 14. 返回申请信息
            WithdrawResultDTO result = new WithdrawResultDTO()
                    .setRequestNo(requestNo)
                    .setAmount(withdrawRequestDTO.getAmount())
                    .setFeeAmount(feeAmount)
                    .setActualAmount(actualAmount)
                    .setStatus(withdrawRequest.getStatus())
                    .setCreateTime(withdrawRequest.getCreateTime());
            
            log.info("创建提现申请成功 - requestNo: {}", requestNo);
            return result;
            
        } catch (BusinessException | IllegalArgumentException e) {
            if (reservedLimit && limitChecker != null) {
                limitChecker.releaseDailyAmount(withdrawRequestDTO.getWithdrawType(), customerNo, withdrawRequestDTO.getAmount());
            }
            throw e;
        } catch (Exception e) {
            if (reservedLimit && limitChecker != null) {
                limitChecker.releaseDailyAmount(withdrawRequestDTO.getWithdrawType(), customerNo, withdrawRequestDTO.getAmount());
            }
            log.error("创建提现申请失败 - customerNo: {}, error: {}", customerNo, e.getMessage(), e);
            throw new BusinessException("创建提现申请失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogAudit(module = "提现管理", operation = "审核提现申请")
    public boolean auditWithdrawRequest(String requestNo, boolean approved, String auditUser, String auditRemark) {
        log.info("审核提现申请 - requestNo: {}, approved: {}, auditUser: {}", 
                requestNo, approved, auditUser);
        
        try {
            // 1. 查询申请
            WithdrawRequest withdrawRequest = withdrawRequestMapper.selectByRequestNo(requestNo);
            if (withdrawRequest == null) {
                throw new BusinessException("提现申请不存在");
            }
            
            // 2. 验证状态
            WithdrawStatus currentStatus = withdrawRequest.getStatusEnum();
            if (currentStatus != WithdrawStatus.PENDING && currentStatus != WithdrawStatus.MANUAL_REVIEW) {
                throw new BusinessException("申请状态不正确，无法审核");
            }
            
            // 3. 更新申请状态
            WithdrawStatus newStatus = approved ? WithdrawStatus.APPROVED : WithdrawStatus.CANCELLED;
            
            if (!currentStatus.canTransitionTo(newStatus)) {
                throw new BusinessException("状态流转非法: " + currentStatus.getDescription() + " -> " + newStatus.getDescription());
            }
            
            int updated = withdrawRequestMapper.auditRequest(requestNo, newStatus.getCode(), auditUser, auditRemark);
            if (updated == 0) {
                throw new BusinessException("申请状态已变更，请刷新后重试");
            }
            
            // 4. 如果拒绝，解冻资金
            if (!approved) {
                boolean unfreezeResult = walletService.unfreezeBalance(
                        withdrawRequest.getCustomerNo(), BizTypeConstants.DEFAULT,
                        withdrawRequest.getAmount(), "审核拒绝，解冻提现资金", requestNo);
                if (!unfreezeResult) {
                    throw new BusinessException("解冻资金失败（乐观锁冲突），请刷新后重试");
                }
                log.info("审核拒绝，已解冻资金 - requestNo: {}, amount: {}",
                        requestNo, withdrawRequest.getAmount());
            }
            
            log.info("审核提现申请成功 - requestNo: {}, status: {}", requestNo, newStatus);
            return true;
            
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // 【安全关键】必须重新抛出异常以触发@Transactional回滚
            // 如果审核状态已更新为CANCELLED但unfreezeBalance失败，吞掉异常会导致事务提交，
            // 造成提现被拒但冻结金额不释放的资金冻结问题
            log.error("审核提现申请失败 - requestNo: {}, error: {}", requestNo, e.getMessage(), e);
            throw new BusinessException("审核提现申请失败: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogAudit(module = "提现管理", operation = "处理提现申请")
    public boolean processWithdrawRequest(String requestNo) {
        log.info("处理提现申请 - requestNo: {}", requestNo);
        
        try {
            // 1. 查询申请
            WithdrawRequest withdrawRequest = withdrawRequestMapper.selectByRequestNo(requestNo);
            if (withdrawRequest == null) {
                throw new BusinessException("提现申请不存在");
            }
            
            // 2. 验证状态
            if (withdrawRequest.getStatusEnum() != WithdrawStatus.APPROVED) {
                throw new BusinessException("申请状态不正确，无法处理");
            }
            
            // 3. 更新状态为处理中
            int processingUpdated = withdrawRequestMapper.updateProcessStatus(requestNo, WithdrawStatus.PROCESSING.getCode(), null, null);
            if (processingUpdated == 0) {
                throw new BusinessException("申请状态已变更，请刷新后重试");
            }
            
            // 4. 获取提现策略并执行提现
            WithdrawStrategy strategy = withdrawStrategyFactory.getStrategy(withdrawRequest.getWithdrawType());
            Map<String, Object> result = strategy.executeWithdraw(withdrawRequest);

            Boolean successObj = (Boolean) result.get("success");
            boolean thirdPartySuccess = successObj != null && successObj;
            String transactionNo = (String) result.get("transactionNo");
            String message = (String) result.get("message");
            String statusStr = (String) result.get("status");

            if (thirdPartySuccess) {
                // 提现成功，扣减冻结余额
                try {
                    boolean deductResult = walletService.deductFrozenBalance(
                            withdrawRequest.getCustomerNo(), BizTypeConstants.DEFAULT,
                            withdrawRequest.getAmount(), true, false);
                    if (!deductResult) {
                        throw new BusinessException("扣减冻结余额失败（乐观锁冲突）");
                    }
                } catch (Exception deductError) {
                    // 【资金安全关键】第三方已扣款但本地扣减冻结余额失败
                    // 标记为 PROCESSING_ERROR，等待人工介入确认，绝不能自动释放冻结资金
                    log.error("第三方提现成功但本地扣减冻结余额异常，标记为PROCESSING_ERROR - requestNo: {}, transactionNo: {}, error: {}",
                            requestNo, transactionNo, deductError.getMessage(), deductError);
                    withdrawRequestMapper.updateProcessStatus(requestNo, WithdrawStatus.PROCESSING_ERROR.getCode(),
                            "第三方已扣款，本地扣减冻结余额异常：" + deductError.getMessage(), transactionNo);

                    // 发送告警MQ消息
                    final WithdrawRequest mqReq = withdrawRequest;
                    final String mqTransactionNo = transactionNo;
                    TransactionSynchronizationUtils.afterCommit(() -> sendWithdrawCompletedMessage(mqReq, WithdrawStatus.PROCESSING_ERROR, mqTransactionNo));
                    throw new BusinessException("提现处理异常，需人工介入确认");
                }

                withdrawRequestMapper.updateProcessStatus(requestNo, WithdrawStatus.SUCCESS.getCode(), message, transactionNo);

                log.info("处理提现申请成功 - requestNo: {}, transactionNo: {}", requestNo, transactionNo);

                // 发送提现完成MQ消息 — 在事务提交后发送，防止脏消息
                final WithdrawRequest mqReq = withdrawRequest;
                final String mqTransactionNo = transactionNo;
                TransactionSynchronizationUtils.afterCommit(() -> sendWithdrawCompletedMessage(mqReq, WithdrawStatus.SUCCESS, mqTransactionNo));
            } else {
                if ("UNKNOWN".equalsIgnoreCase(statusStr)) {
                    log.warn("第三方提现返回未知状态或网络超时，标记为 PROCESSING_ERROR 并挂起资金 - requestNo: {}", requestNo);
                    withdrawRequestMapper.updateProcessStatus(requestNo, WithdrawStatus.PROCESSING_ERROR.getCode(),
                            "支付状态未知，挂起等待对账补偿: " + message, transactionNo);

                    final WithdrawRequest mqReq = withdrawRequest;
                    final String mqTransactionNo = transactionNo;
                    TransactionSynchronizationUtils.afterCommit(() -> sendWithdrawCompletedMessage(mqReq, WithdrawStatus.PROCESSING_ERROR, mqTransactionNo));

                    return false;
                }

                // 提现失败，解冻资金
                withdrawRequestMapper.updateProcessStatus(requestNo, WithdrawStatus.FAILED.getCode(), message, transactionNo);

                walletService.unfreezeBalance(
                        withdrawRequest.getCustomerNo(), BizTypeConstants.DEFAULT,
                        withdrawRequest.getAmount(), "提现失败，解冻资金", requestNo);

                log.warn("处理提现申请失败 - requestNo: {}, reason: {}", requestNo, message);

                // 发送提现失败MQ消息 — 在事务提交后发送
                final WithdrawRequest mqReq = withdrawRequest;
                final String mqTransactionNo = transactionNo;
                TransactionSynchronizationUtils.afterCommit(() -> sendWithdrawCompletedMessage(mqReq, WithdrawStatus.FAILED, mqTransactionNo));
            }

            return thirdPartySuccess;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("处理提现申请异常 - requestNo: {}, error: {}", requestNo, e.getMessage(), e);

            // 【资金安全关键】在strategy.executeWithdraw()执行前抛出的异常，可以安全解冻
            // 无法确定第三方是否已扣款，标记为 PROCESSING_ERROR，不自动解冻
            try {
                WithdrawRequest latestReq = withdrawRequestMapper.selectByRequestNo(requestNo);
                if (latestReq != null && latestReq.getStatusEnum() == WithdrawStatus.PROCESSING) {
                    withdrawRequestMapper.updateProcessStatus(requestNo, WithdrawStatus.PROCESSING_ERROR.getCode(),
                            "处理异常，需人工介入：" + e.getMessage(), null);

                    // 发送告警MQ消息
                    final String mqRequestNo = requestNo;
                    TransactionSynchronizationUtils.afterCommit(() -> {
                        try {
                            WithdrawRequest errReq = withdrawRequestMapper.selectByRequestNo(mqRequestNo);
                            if (errReq != null) {
                                sendWithdrawCompletedMessage(errReq, WithdrawStatus.PROCESSING_ERROR, null);
                            }
                        } catch (Exception mqEx) {
                            log.error("发送提现异常MQ消息失败 - requestNo: {}", mqRequestNo, mqEx);
                        }
                    });
                }
            } catch (Exception ex) {
                log.error("更新PROCESSING_ERROR状态异常 - requestNo: {}", requestNo, ex);
            }

            return false;
        }
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogAudit(module = "提现管理", operation = "取消提现申请")
    public boolean cancelWithdrawRequest(String customerNo, String requestNo, String cancelReason) {
        log.info("取消提现申请 - customerNo: {}, requestNo: {}", customerNo, requestNo);
        
        try {
            // 1. 查询申请
            WithdrawRequest withdrawRequest = withdrawRequestMapper.selectByRequestNo(requestNo);
            if (withdrawRequest == null) {
                throw new BusinessException("提现申请不存在");
            }
            
            // 2. 验证权限
            if (!withdrawRequest.getCustomerNo().equals(customerNo)) {
                throw new BusinessException("无权取消该申请");
            }
            
            // 3. 验证状态（仅允许待审核状态取消，审核通过后应由管理员操作）
            WithdrawStatus currentStatus = withdrawRequest.getStatusEnum();
            if (currentStatus != WithdrawStatus.PENDING
                    && currentStatus != WithdrawStatus.MANUAL_REVIEW) {
                throw new BusinessException("申请状态不正确，无法取消（审核通过后请联系管理员处理）");
            }
            
            // 4. 更新状态
            int updated = withdrawRequestMapper.cancelRequest(requestNo, cancelReason);
            if (updated == 0) {
                throw new BusinessException("申请状态已变更，请刷新后重试");
            }
            
            // 5. 解冻资金
            boolean unfreezeResult = walletService.unfreezeBalance(
                    withdrawRequest.getCustomerNo(), BizTypeConstants.DEFAULT,
                    withdrawRequest.getAmount(), "取消提现，解冻资金", requestNo);
            if (!unfreezeResult) {
                throw new BusinessException("解冻资金失败（乐观锁冲突），请刷新后重试");
            }

            log.info("取消提现申请成功 - requestNo: {}", requestNo);
            return true;
            
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // 【安全关键】必须重新抛出异常以触发@Transactional回滚
            // 如果取消状态已更新但unfreezeBalance失败，吞掉异常会导致事务提交，
            // 造成提现已取消但冻结金额不释放的资金冻结问题
            log.error("取消提现申请失败 - requestNo: {}, error: {}", requestNo, e.getMessage(), e);
            throw new BusinessException("取消提现申请失败: " + e.getMessage(), e);
        }
    }

    @Override
    public WithdrawResultDTO getWithdrawRequestStatus(String customerNo, String requestNo) {
        log.info("查询提现申请状态 - customerNo: {}, requestNo: {}", customerNo, requestNo);

        try {
            WithdrawRequest withdrawRequest = withdrawRequestMapper.selectByRequestNo(requestNo);
            if (withdrawRequest == null) {
                throw new BusinessException("提现申请不存在");
            }

            if (!withdrawRequest.getCustomerNo().equals(customerNo)) {
                throw new BusinessException("无权查询该申请");
            }

            return new WithdrawResultDTO()
                    .setRequestNo(withdrawRequest.getRequestNo())
                    .setAmount(withdrawRequest.getAmount())
                    .setFeeAmount(withdrawRequest.getFeeAmount())
                    .setActualAmount(withdrawRequest.getActualAmount())
                    .setPlatform(withdrawRequest.getPlatform())
                    .setStatus(withdrawRequest.getStatus())
                    .setCreateTime(withdrawRequest.getCreateTime())
                    .setAuditTime(withdrawRequest.getAuditTime())
                    .setProcessTime(withdrawRequest.getProcessTime());
            
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("查询提现申请状态失败 - requestNo: {}, error: {}", requestNo, e.getMessage(), e);
            throw new BusinessException("查询提现申请状态失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 验证提现请求参数
     */
    private void validateWithdrawRequest(String customerNo, WithdrawRequestDTO withdrawRequest) {
        PaymentValidateUtils.notBlank(customerNo, "用户编号不能为空");
        PaymentValidateUtils.notNull(withdrawRequest, "提现请求参数不能为空");
        PaymentValidateUtils.positive(withdrawRequest.getAmount(), "提现金额必须大于0");
        PaymentValidateUtils.notBlank(withdrawRequest.getWithdrawType(), "提现平台不能为空");
        PaymentValidateUtils.notBlank(withdrawRequest.getAccountNumber(), "账户信息不能为空");
        PaymentValidateUtils.notBlank(withdrawRequest.getAccountName(), "账户名称不能为空");
        
        PaymentValidateUtils.inRange(withdrawRequest.getAmount(),
                PaymentConstants.MIN_WITHDRAW_AMOUNT_FEN,
                PaymentConstants.MAX_WITHDRAW_AMOUNT_FEN,
                "提现金额需在100元至1000万元之间");
        
        WithdrawStrategy strategy = withdrawStrategyFactory.getStrategy(withdrawRequest.getWithdrawType());
        PaymentValidateUtils.isTrue(withdrawRequest.getAmount() >= strategy.getMinAmount(),
                () -> "提现金额不能小于最小金额: " + strategy.getMinAmount() / 100.0 + "元");
        PaymentValidateUtils.isTrue(withdrawRequest.getAmount() <= strategy.getMaxAmount(),
                () -> "提现金额不能大于最大金额: " + strategy.getMaxAmount() / 100.0 + "元");
    }

    @Override
    public Page<WithdrawRequest> queryRequests(WithdrawRequestQueryDTO query, int pageNum, int pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        return (Page<WithdrawRequest>) withdrawRequestMapper.selectByQuery(query);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogAudit(module = "提现管理", operation = "审核提现申请")
    public void auditRequest(WithdrawAuditDTO audit) {
        WithdrawRequest request = withdrawRequestMapper.selectById(audit.getId());
        if (request == null) {
            throw new BusinessException("申请不存在");
        }
        
        WithdrawStatus currentStatus = request.getStatusEnum();
        if (currentStatus != WithdrawStatus.PENDING && currentStatus != WithdrawStatus.MANUAL_REVIEW) {
            throw new BusinessException("该申请状态不可审核");
        }
        
        WithdrawStatus newStatus = WithdrawStatus.getByCode(audit.getStatus());
        if (newStatus == null) {
            throw new BusinessException("无效的审核状态: " + audit.getStatus());
        }
        
        if (!currentStatus.canTransitionTo(newStatus)) {
            throw new BusinessException("状态流转非法: " + currentStatus.getDescription() + " -> " + newStatus.getDescription());
        }

        request.setStatusEnum(newStatus);
        request.setAuditRemark(audit.getRemark());
        request.setAuditUser(audit.getAuditBy());
        
        withdrawRequestMapper.auditRequest(request.getRequestNo(), newStatus.getCode(), audit.getAuditBy(), audit.getRemark());

        if (newStatus == WithdrawStatus.REJECTED || newStatus == WithdrawStatus.CANCELLED) {
             boolean unfreezeResult = walletService.unfreezeBalance(
                     request.getCustomerNo(), BizTypeConstants.DEFAULT,
                     request.getAmount(), "审核拒绝/取消，解冻提现资金", request.getRequestNo());
             if (!unfreezeResult) {
                 throw new BusinessException("解冻资金失败（乐观锁冲突），请刷新后重试");
             }
        }
    }

    private Long getDailyTotalLimit(String platform) {
        if (withdrawConfig == null) {
            return null;
        }
        if (PaymentConstants.PLATFORM_WECHAT.equalsIgnoreCase(platform)) {
            return withdrawConfig.getWechat().getDailyTotalLimit();
        }
        if (PaymentConstants.PLATFORM_ALIPAY.equalsIgnoreCase(platform)) {
            return withdrawConfig.getAlipay() != null ? withdrawConfig.getAlipay().getDailyTotalLimit() : null;
        }
        if (PaymentConstants.PLATFORM_DOUYIN.equalsIgnoreCase(platform)) {
            return withdrawConfig.getDouyin() != null ? withdrawConfig.getDouyin().getDailyTotalLimit() : null;
        }
        log.warn("未知提现平台，不限制日总额度 - platform: {}", platform);
        return null;
    }

    private Long getDailyUserLimit(String platform) {
        if (withdrawConfig == null) {
            return null;
        }
        if (PaymentConstants.PLATFORM_WECHAT.equalsIgnoreCase(platform)) {
            return withdrawConfig.getWechat().getDailyUserLimit();
        }
        if (PaymentConstants.PLATFORM_ALIPAY.equalsIgnoreCase(platform)) {
            return withdrawConfig.getAlipay() != null ? withdrawConfig.getAlipay().getDailyUserLimit() : null;
        }
        if (PaymentConstants.PLATFORM_DOUYIN.equalsIgnoreCase(platform)) {
            return withdrawConfig.getDouyin() != null ? withdrawConfig.getDouyin().getDailyUserLimit() : null;
        }
        log.warn("未知提现平台，不限制个人日额度 - platform: {}", platform);
        return null;
    }

    /**
     * 发送提现完成MQ消息
     */
    private void sendWithdrawCompletedMessage(WithdrawRequest req, WithdrawStatus status, String transactionNo) {
        try {
            WithdrawCompletedMessage msg = new WithdrawCompletedMessage();
            msg.setRequestNo(req.getRequestNo());
            msg.setCustomerNo(req.getCustomerNo());
            msg.setAmountFen(req.getAmount());
            msg.setFeeFen(req.getFeeAmount());
            msg.setActualAmountFen(req.getActualAmount());
            msg.setStatus(status.name());
            paymentEventProducer.sendWithdrawCompleted(msg);
        } catch (Exception e) {
            log.error("发送提现完成MQ消息失败 - requestNo: {}", req.getRequestNo(), e);
        }
    }
}
