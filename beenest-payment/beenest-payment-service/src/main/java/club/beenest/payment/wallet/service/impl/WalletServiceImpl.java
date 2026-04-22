package club.beenest.payment.wallet.service.impl;

import club.beenest.payment.common.annotation.LogAudit;
import club.beenest.payment.common.exception.BusinessException;
import club.beenest.payment.common.utils.MoneyUtil;
import club.beenest.payment.common.utils.TransactionSynchronizationUtils;
import club.beenest.payment.shared.constant.BizTypeConstants;
import club.beenest.payment.wallet.event.WalletBalanceChangedEvent;
import club.beenest.payment.wallet.mapper.WalletMapper;
import club.beenest.payment.wallet.mapper.WalletTransactionMapper;
import club.beenest.payment.wallet.mq.BalanceChangedMessage;
import club.beenest.payment.paymentorder.mq.producer.PaymentEventProducer;
import club.beenest.payment.wallet.dto.TransactionQueryDTO;
import club.beenest.payment.wallet.dto.TransactionHistoryDTO;
import club.beenest.payment.wallet.dto.TransactionParam;
import club.beenest.payment.wallet.dto.WalletBalanceDTO;
import club.beenest.payment.wallet.dto.WalletAdminQueryDTO;
import club.beenest.payment.wallet.domain.entity.Wallet;
import club.beenest.payment.wallet.domain.entity.WalletTransaction;
import club.beenest.payment.wallet.domain.enums.WalletReferenceType;
import club.beenest.payment.wallet.domain.enums.WalletStatus;
import club.beenest.payment.wallet.domain.enums.WalletTransactionStatus;
import club.beenest.payment.wallet.domain.enums.WalletTransactionType;
import club.beenest.payment.wallet.security.BalanceHashCalculator;
import club.beenest.payment.wallet.service.IWalletService;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.UUID;

@Slf4j
@Service
public class WalletServiceImpl implements IWalletService {

    @Autowired
    private WalletMapper walletMapper;

    @Autowired
    private WalletTransactionMapper walletTransactionMapper;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PaymentEventProducer paymentEventProducer;

    // ==================== 钱包基础操作 ====================

    @Override
    @LogAudit
    public BigDecimal getBalance(String customerNo, String bizType) {
        String resolvedBizType = resolveBizType(bizType);
        log.info("查询用户余额：用户={}, bizType={}", customerNo, resolvedBizType);

        if (!StringUtils.hasText(customerNo)) {
            throw new IllegalArgumentException("用户编号不能为空");
        }

        try {
            Wallet wallet = getWallet(customerNo, resolvedBizType);
            if (wallet == null) {
                log.info("用户钱包不存在，返回零余额：用户={}, bizType={}", customerNo, resolvedBizType);
                return BigDecimal.ZERO;
            }
            BigDecimal balance = wallet.getBalanceInYuan();
            log.info("用户余额查询成功：用户={}, 余额={}", customerNo, balance);
            return balance;
        } catch (Exception e) {
            log.error("查询用户余额失败：用户={}, 错误={}", customerNo, e.getMessage(), e);
            throw new BusinessException("查询余额失败：" + e.getMessage(), e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogAudit
    public void addBalance(String customerNo, String bizType, BigDecimal amount, String description,
                          String transactionType, String referenceNo) {
        String resolvedBizType = resolveBizType(bizType);
        executeBalanceOperation(customerNo, resolvedBizType, amount, description, transactionType,
                referenceNo, BalanceOperationType.ADD);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogAudit
    public boolean deductBalance(String customerNo, String bizType, BigDecimal amount, String description,
                                String transactionType, String referenceNo) {
        String resolvedBizType = resolveBizType(bizType);
        return (Boolean) executeBalanceOperation(customerNo, resolvedBizType, amount, description,
                transactionType, referenceNo, BalanceOperationType.DEDUCT);
    }

    // ==================== 钱包管理 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogAudit
    public Wallet createWallet(String customerNo, String bizType) {
        String resolvedBizType = resolveBizType(bizType);
        log.info("创建用户钱包：用户={}, bizType={}", customerNo, resolvedBizType);

        if (!StringUtils.hasText(customerNo)) {
            throw new IllegalArgumentException("用户编号不能为空");
        }

        try {
            String walletNo = generateWalletNo();

            Wallet wallet = new Wallet();
            wallet.setWalletNo(walletNo);
            wallet.setCustomerNo(customerNo);
            wallet.setBizType(resolvedBizType);
            wallet.setBalance(0L);
            wallet.setFrozenBalance(0L);
            wallet.setTotalRecharge(0L);
            wallet.setTotalWithdraw(0L);
            wallet.setTotalConsume(0L);
            wallet.setStatus(WalletStatus.ACTIVE.getCode());
            wallet.setVersion(0);
            wallet.setBalanceHash(BalanceHashCalculator.calculate(wallet));
            wallet.setCreateTime(LocalDateTime.now());
            wallet.setUpdateTime(LocalDateTime.now());

            int insertResult = walletMapper.insert(wallet);
            if (insertResult <= 0) {
                throw new BusinessException("创建钱包失败");
            }

            log.info("创建用户钱包成功：用户={}, 钱包编号={}, bizType={}", customerNo, walletNo, resolvedBizType);
            return wallet;

        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发场景下，UNIQUE(customer_no, biz_type) 约束命中，直接返回已有钱包
            log.warn("钱包已存在，返回已有钱包：用户={}, bizType={}", customerNo, resolvedBizType);
            return walletMapper.selectByCustomerNoAndBizType(customerNo, resolvedBizType);
        } catch (Exception e) {
            log.error("创建用户钱包失败：用户={}, 错误={}", customerNo, e.getMessage(), e);
            throw new BusinessException("创建钱包失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Wallet getWallet(String customerNo, String bizType) {
        String resolvedBizType = resolveBizType(bizType);
        log.info("查询用户钱包：用户={}, bizType={}", customerNo, resolvedBizType);

        if (!StringUtils.hasText(customerNo)) {
            throw new IllegalArgumentException("用户编号不能为空");
        }

        try {
            Wallet wallet = walletMapper.selectByCustomerNoAndBizType(customerNo, resolvedBizType);
            if (wallet == null) {
                log.warn("用户钱包不存在：用户={}, bizType={}", customerNo, resolvedBizType);
                return null;
            }

            log.info("查询用户钱包成功：用户={}, 钱包编号={}", customerNo, wallet.getWalletNo());
            return wallet;

        } catch (Exception e) {
            log.error("查询用户钱包失败：用户={}, 错误={}", customerNo, e.getMessage(), e);
            throw new BusinessException("查询钱包失败：" + e.getMessage(), e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Wallet getOrCreateWallet(String customerNo, String bizType) {
        String resolvedBizType = resolveBizType(bizType);
        log.info("获取或创建用户钱包：用户={}, bizType={}", customerNo, resolvedBizType);

        // 先查询，快速路径
        Wallet wallet = getWallet(customerNo, resolvedBizType);
        if (wallet != null) {
            return wallet;
        }

        // 尝试创建，利用数据库 UNIQUE 约束防并发重复
        try {
            return createWallet(customerNo, resolvedBizType);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发创建冲突，重新查询
            log.warn("并发创建钱包冲突，重新查询：用户={}, bizType={}", customerNo, resolvedBizType);
            return walletMapper.selectByCustomerNoAndBizType(customerNo, resolvedBizType);
        }
    }

    // ==================== 冻结/解冻操作 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean freezeBalance(String customerNo, String bizType, Long amountInCents,
                                 String description, String referenceNo) {
        String resolvedBizType = resolveBizType(bizType);
        log.info("冻结余额：用户={}, bizType={}, 金额={}分, 关联单号={}", customerNo, resolvedBizType, amountInCents, referenceNo);

        if (amountInCents == null || amountInCents <= 0) {
            throw new IllegalArgumentException("冻结金额必须大于0");
        }

        Wallet wallet = getWallet(customerNo, resolvedBizType);
        if (wallet == null) {
            throw new BusinessException("用户钱包不存在");
        }
        if (!wallet.isActive()) {
            throw new BusinessException("钱包状态异常，无法冻结");
        }
        if (wallet.getBalance() < amountInCents) {
            throw new BusinessException("可用余额不足，无法冻结");
        }

        String freezeHash = BalanceHashCalculator.calculate(
                wallet.getBalance() - amountInCents,
                wallet.getFrozenBalance() + amountInCents,
                wallet.getVersion() + 1,
                wallet.getWalletNo());

        int result = walletMapper.freezeBalance(wallet.getWalletNo(), amountInCents, wallet.getVersion(), freezeHash);
        if (result == 0) {
            log.warn("冻结余额失败（乐观锁冲突）：用户={}", customerNo);
            return false;
        }

        // 记录交易流水
        recordFreezeTransaction(wallet, customerNo, resolvedBizType, amountInCents, description, referenceNo, "FREEZE");

        log.info("冻结余额成功：用户={}, 金额={}分", customerNo, amountInCents);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean unfreezeBalance(String customerNo, String bizType, Long amountInCents,
                                   String description, String referenceNo) {
        String resolvedBizType = resolveBizType(bizType);
        log.info("解冻余额：用户={}, bizType={}, 金额={}分, 关联单号={}", customerNo, resolvedBizType, amountInCents, referenceNo);

        if (amountInCents == null || amountInCents <= 0) {
            throw new IllegalArgumentException("解冻金额必须大于0");
        }

        Wallet wallet = getWallet(customerNo, resolvedBizType);
        if (wallet == null) {
            throw new BusinessException("用户钱包不存在");
        }

        String unfreezeHash = BalanceHashCalculator.calculate(
                wallet.getBalance() + amountInCents,
                wallet.getFrozenBalance() - amountInCents,
                wallet.getVersion() + 1,
                wallet.getWalletNo());

        int result = walletMapper.unfreezeBalance(wallet.getWalletNo(), amountInCents, wallet.getVersion(), unfreezeHash);
        if (result == 0) {
            log.warn("解冻余额失败（乐观锁冲突）：用户={}", customerNo);
            return false;
        }

        // 记录交易流水
        recordFreezeTransaction(wallet, customerNo, resolvedBizType, amountInCents, description, referenceNo, "UNFREEZE");

        log.info("解冻余额成功：用户={}, 金额={}分", customerNo, amountInCents);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deductFrozenBalance(String customerNo, String bizType, Long amountInCents,
                                        boolean isWithdraw, boolean isConsume) {
        String resolvedBizType = resolveBizType(bizType);
        log.info("扣减冻结余额：用户={}, bizType={}, 金额={}分, isWithdraw={}, isConsume={}",
                customerNo, resolvedBizType, amountInCents, isWithdraw, isConsume);

        if (amountInCents == null || amountInCents <= 0) {
            throw new IllegalArgumentException("扣减金额必须大于0");
        }

        Wallet wallet = getWallet(customerNo, resolvedBizType);
        if (wallet == null) {
            throw new BusinessException("用户钱包不存在");
        }

        String deductHash = BalanceHashCalculator.calculate(
                wallet.getBalance(),
                wallet.getFrozenBalance() - amountInCents,
                wallet.getVersion() + 1,
                wallet.getWalletNo());

        int result = walletMapper.deductFrozenBalance(wallet.getWalletNo(), amountInCents,
                wallet.getVersion(), isWithdraw, isConsume, deductHash);
        if (result == 0) {
            log.warn("扣减冻结余额失败（乐观锁冲突）：用户={}", customerNo);
            return false;
        }

        log.info("扣减冻结余额成功：用户={}, 金额={}分", customerNo, amountInCents);
        return true;
    }

    @Override
    public Wallet getWalletByWalletNo(String walletNo) {
        if (!StringUtils.hasText(walletNo)) {
            throw new IllegalArgumentException("钱包编号不能为空");
        }
        return walletMapper.selectByWalletNo(walletNo);
    }

    // ==================== 余额查询 ====================

    @Override
    public WalletBalanceDTO getWalletBalance(String customerNo, String bizType) {
        String resolvedBizType = resolveBizType(bizType);
        log.info("查询钱包余额信息：用户={}, bizType={}", customerNo, resolvedBizType);

        if (!StringUtils.hasText(customerNo)) {
            throw new IllegalArgumentException("用户编号不能为空");
        }

        try {
            Wallet wallet = getWallet(customerNo, resolvedBizType);

            WalletBalanceDTO balanceDTO = new WalletBalanceDTO();
            if (wallet != null) {
                balanceDTO.setBalance(wallet.getBalance());
                balanceDTO.setFrozenBalance(wallet.getFrozenBalance());
                balanceDTO.setTotalRecharge(wallet.getTotalRecharge());
                balanceDTO.setTotalWithdraw(wallet.getTotalWithdraw());
                balanceDTO.setTotalConsume(wallet.getTotalConsume());
            }
            // TODO: 红包和优惠券功能迁移后对接
            balanceDTO.setRedPacketBalance(0L);
            balanceDTO.setCouponCount(0);

            log.info("查询钱包余额信息成功：用户={}, 余额={}", customerNo, balanceDTO.getBalanceInYuan());
            return balanceDTO;

        } catch (Exception e) {
            log.error("查询钱包余额信息失败：用户={}, 错误={}", customerNo, e.getMessage(), e);
            throw new BusinessException("查询钱包余额失败：" + e.getMessage(), e);
        }
    }

    // ==================== 交易历史 ====================

    @Override
    public Page<TransactionHistoryDTO> getTransactionHistory(String customerNo, String bizType,
                                                              Integer pageNum, Integer pageSize, String transactionType) {
        String resolvedBizType = resolveBizType(bizType);
        log.info("查询交易历史：用户={}, bizType={}, 页码={}, 页大小={}, 交易类型={}",
                customerNo, resolvedBizType, pageNum, pageSize, transactionType);

        if (!StringUtils.hasText(customerNo)) {
            throw new IllegalArgumentException("用户编号不能为空");
        }
        if (pageNum == null || pageNum < 1) {
            pageNum = 1;
        }
        if (pageSize == null || pageSize < 1 || pageSize > 100) {
            pageSize = 20;
        }

        try {
            PageHelper.startPage(pageNum, pageSize);
            Page<WalletTransaction> transactionPage = walletTransactionMapper.selectByCustomerNo(customerNo, transactionType);

            Page<TransactionHistoryDTO> resultPage = new Page<>();
            resultPage.setPageNum(transactionPage.getPageNum());
            resultPage.setPageSize(transactionPage.getPageSize());
            resultPage.setTotal(transactionPage.getTotal());
            resultPage.setPages(transactionPage.getPages());

            for (WalletTransaction transaction : transactionPage.getResult()) {
                TransactionHistoryDTO dto = convertToTransactionHistoryDTO(transaction);
                resultPage.getResult().add(dto);
            }

            log.info("查询交易历史成功：用户={}, 总记录数={}", customerNo, resultPage.getTotal());
            return resultPage;

        } catch (Exception e) {
            log.error("查询交易历史失败：用户={}, 错误={}", customerNo, e.getMessage(), e);
            throw new BusinessException("查询交易历史失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Page<TransactionHistoryDTO> queryTransactions(TransactionQueryDTO query, Integer pageNum, Integer pageSize) {
        if (query == null) {
            query = new TransactionQueryDTO();
        }
        if (pageNum == null || pageNum < 1) {
            pageNum = 1;
        }
        if (pageSize == null || pageSize < 1 || pageSize > 100) {
            pageSize = 20;
        }

        PageHelper.startPage(pageNum, pageSize);
        Page<WalletTransaction> transactionPage = walletTransactionMapper.selectByQuery(query);

        Page<TransactionHistoryDTO> resultPage = new Page<>();
        resultPage.setPageNum(transactionPage.getPageNum());
        resultPage.setPageSize(transactionPage.getPageSize());
        resultPage.setTotal(transactionPage.getTotal());
        resultPage.setPages(transactionPage.getPages());

        for (WalletTransaction transaction : transactionPage.getResult()) {
            TransactionHistoryDTO dto = convertToTransactionHistoryDTO(transaction);
            resultPage.getResult().add(dto);
        }

        return resultPage;
    }

    @Override
    public Page<Wallet> queryWallets(WalletAdminQueryDTO query, Integer pageNum, Integer pageSize) {
        if (query == null) {
            query = new WalletAdminQueryDTO();
        }
        if (pageNum == null || pageNum < 1) {
            pageNum = 1;
        }
        if (pageSize == null || pageSize < 1 || pageSize > 100) {
            pageSize = 20;
        }

        PageHelper.startPage(pageNum, pageSize);
        return (Page<Wallet>) walletMapper.selectAllWithConditions(
                query.getCustomerNo(), query.getWalletNo(), query.getStatus(), query.getBizType());
    }

    // ==================== 内部查询（供 drone-system Feign 调用） ====================

    @Override
    public Page<WalletTransaction> getTransactionsByCustomerNo(String customerNo, String transactionType,
                                                                Integer pageNum, Integer pageSize) {
        // 参数校验：与同类方法保持一致
        if (pageNum == null || pageNum < 1) {
            pageNum = 1;
        }
        if (pageSize == null || pageSize < 1 || pageSize > 100) {
            pageSize = 20;
        }
        PageHelper.startPage(pageNum, pageSize);
        return walletTransactionMapper.selectByCustomerNo(customerNo, transactionType);
    }

    @Override
    public java.util.List<WalletTransaction> getIncomeStatistics(String customerNo, String startTime, String endTime) {
        LocalDateTime start = null;
        LocalDateTime end = null;
        try {
            if (StringUtils.hasText(startTime)) {
                start = LocalDateTime.parse(startTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            }
            if (StringUtils.hasText(endTime)) {
                end = LocalDateTime.parse(endTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            }
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("时间格式不正确，应为 ISO 格式（如 2026-01-01T00:00:00）");
        }
        return walletTransactionMapper.statisticsByCustomerNo(customerNo, start, end);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 解析 bizType，为空时使用默认值
     */
    private String resolveBizType(String bizType) {
        return StringUtils.hasText(bizType) ? bizType : BizTypeConstants.DEFAULT;
    }

    private WalletTransaction createTransactionRecord(TransactionParam param) {
        WalletTransaction transaction = new WalletTransaction();
        transaction.setTransactionNo(param.getTransactionNo());
        transaction.setWalletNo(param.getWalletNo());
        transaction.setCustomerNo(param.getCustomerNo());
        transaction.setBizType(param.getBizType());
        transaction.setTransactionType(param.getTransactionType());
        transaction.setAmount(param.getAmount());
        transaction.setBeforeBalance(param.getBeforeBalance());
        transaction.setAfterBalance(param.getAfterBalance());
        transaction.setDescription(param.getDescription());
        transaction.setReferenceNo(param.getReferenceNo());
        transaction.setReferenceType(param.getReferenceType());
        transaction.setStatus(param.getStatus());
        transaction.setCreateTime(LocalDateTime.now());
        return transaction;
    }

    private String getReferenceType(String transactionType) {
        WalletTransactionType type = WalletTransactionType.getByCode(transactionType);
        // PILOT_INCOME 关联订单，通过 code 字符串匹配避免枚举版本编译问题
        if (type != null && "PILOT_INCOME".equals(type.getCode())) {
            return WalletReferenceType.ORDER.getCode();
        }
        return switch (type) {
            case RECHARGE -> WalletReferenceType.PAYMENT_ORDER.getCode();
            case WITHDRAW -> WalletReferenceType.WITHDRAW_REQUEST.getCode();
            case PAYMENT -> WalletReferenceType.ORDER.getCode();
            case RED_PACKET_CONVERT -> WalletReferenceType.RED_PACKET.getCode();
            default -> null;
        };
    }

    private TransactionHistoryDTO convertToTransactionHistoryDTO(WalletTransaction transaction) {
        TransactionHistoryDTO dto = new TransactionHistoryDTO();
        dto.setTransactionNo(transaction.getTransactionNo());
        dto.setTransactionType(transaction.getTransactionType());
        dto.setTransactionTypeDisplayName(transaction.getTransactionTypeDisplayName());
        dto.setAmount(transaction.getAmount());
        dto.setDescription(transaction.getDescription());
        dto.setStatus(transaction.getStatus());
        dto.setStatusDisplayName(transaction.getStatusDisplayName());
        dto.setReferenceNo(transaction.getReferenceNo());
        dto.setReferenceType(transaction.getReferenceType());
        dto.setCreateTime(transaction.getCreateTime());
        dto.setRemark(transaction.getRemark());
        dto.setCustomerNo(transaction.getCustomerNo());
        dto.setCustomerName(transaction.getCustomerName());
        dto.setCustomerPhone(transaction.getCustomerPhone());
        return dto;
    }

    private String generateWalletNo() {
        return "W" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private String generateTransactionNo() {
        return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private void validateTransactionParams(String customerNo, BigDecimal amount, String description, String transactionType) {
        if (!StringUtils.hasText(customerNo)) {
            throw new IllegalArgumentException("用户编号不能为空");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("金额必须大于0");
        }
        if (!StringUtils.hasText(description)) {
            throw new IllegalArgumentException("交易描述不能为空");
        }
        if (!StringUtils.hasText(transactionType)) {
            throw new IllegalArgumentException("交易类型不能为空");
        }
    }

    private void recordFreezeTransaction(Wallet wallet, String customerNo, String bizType,
                                          Long amountInCents, String description, String referenceNo,
                                          String transactionType) {
        String transactionNo = generateTransactionNo();
        TransactionParam param = new TransactionParam();
        param.setTransactionNo(transactionNo);
        param.setWalletNo(wallet.getWalletNo());
        param.setCustomerNo(customerNo);
        param.setBizType(bizType);
        param.setTransactionType(transactionType);
        param.setAmount(amountInCents);
        param.setBeforeBalance(wallet.getBalance());
        param.setAfterBalance(wallet.getBalance());
        param.setDescription(description);
        param.setReferenceNo(referenceNo);
        param.setStatus(WalletTransactionStatus.SUCCESS.getCode());

        WalletTransaction transaction = createTransactionRecord(param);
        walletTransactionMapper.insert(transaction);
    }

    private enum BalanceOperationType {
        ADD,
        DEDUCT
    }

    private Object executeBalanceOperation(String customerNo, String bizType, BigDecimal amount, String description,
            String transactionType, String referenceNo, BalanceOperationType opType) {
        log.info("{}用户余额：用户={}, bizType={}, 金额={}, 描述={}, 类型={}, 关联单号={}",
                opType == BalanceOperationType.ADD ? "增加" : "扣减",
                customerNo, bizType, amount, description, transactionType, referenceNo);

        validateTransactionParams(customerNo, amount, description, transactionType);

        Wallet wallet = getOrCreateWallet(customerNo, bizType);

        if (!wallet.isActive()) {
            if (opType == BalanceOperationType.ADD) {
                throw new BusinessException("钱包状态异常，无法进行操作");
            }
            log.warn("钱包状态异常，无法扣减余额：用户={}, 状态={}", customerNo, wallet.getStatus());
            return false;
        }

        Long amountInCents = MoneyUtil.yuanToCents(amount);

        if (StringUtils.hasText(referenceNo)) {
            int existingCount = walletTransactionMapper.countByReferenceNo(referenceNo);
            if (existingCount > 0) {
                log.warn("交易已存在，拒绝重复处理：用户={}, 关联单号={}", customerNo, referenceNo);
                if (opType == BalanceOperationType.ADD) {
                    throw new BusinessException("交易已存在，请勿重复提交");
                }
                return false;
            }
        }

        if (opType == BalanceOperationType.DEDUCT && !wallet.hasEnoughBalance(amountInCents)) {
            log.warn("余额不足，无法扣减：用户={}, 当前余额={}, 扣减金额={}",
                    customerNo, wallet.getBalanceInYuan(), amount);
            return false;
        }

        Long beforeBalance = wallet.getBalance();
        boolean isRecharge = WalletTransactionType.RECHARGE.getCode().equals(transactionType);
        boolean isWithdraw = WalletTransactionType.WITHDRAW.getCode().equals(transactionType);
        boolean isConsume = WalletTransactionType.PAYMENT.getCode().equals(transactionType)
                || WalletTransactionType.FEE.getCode().equals(transactionType)
                || WalletTransactionType.PENALTY.getCode().equals(transactionType);

        int retryCount = 0;
        int maxRetries = 3;

        while (retryCount < maxRetries) {
            long afterBalance = opType == BalanceOperationType.ADD
                    ? beforeBalance + amountInCents
                    : beforeBalance - amountInCents;

            try {
                int updateResult;
                String newBalanceHash = BalanceHashCalculator.calculate(
                        afterBalance,
                        wallet.getFrozenBalance(),
                        wallet.getVersion() + 1,
                        wallet.getWalletNo());
                if (opType == BalanceOperationType.ADD) {
                    updateResult = walletMapper.addBalance(wallet.getWalletNo(), amountInCents,
                            wallet.getVersion(), isRecharge, newBalanceHash);
                } else {
                    updateResult = walletMapper.deductBalance(wallet.getWalletNo(), amountInCents,
                            wallet.getVersion(), isWithdraw, isConsume, newBalanceHash);
                }

                if (updateResult > 0) {
                    // 乐观锁更新成功，才插入交易流水记录
                    String transactionNo = generateTransactionNo();
                    TransactionParam param = new TransactionParam();
                    param.setTransactionNo(transactionNo);
                    param.setWalletNo(wallet.getWalletNo());
                    param.setCustomerNo(customerNo);
                    param.setBizType(bizType);
                    param.setTransactionType(transactionType);
                    param.setAmount(opType == BalanceOperationType.ADD ? amountInCents : -amountInCents);
                    param.setBeforeBalance(beforeBalance);
                    param.setAfterBalance(afterBalance);
                    param.setDescription(description);
                    param.setReferenceNo(referenceNo);
                    param.setReferenceType(getReferenceType(transactionType));
                    param.setStatus(WalletTransactionStatus.SUCCESS.getCode());

                    WalletTransaction transaction = createTransactionRecord(param);
                    int insertResult = walletTransactionMapper.insert(transaction);
                    if (insertResult <= 0) {
                        throw new BusinessException("创建交易流水失败");
                    }

                    log.info("{}用户余额成功：用户={}, bizType={}, 金额={}, 交易前余额={}, 交易后余额={}, 交易号={}",
                            opType == BalanceOperationType.ADD ? "增加" : "扣减",
                            customerNo, bizType, amount,
                            new BigDecimal(beforeBalance).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP),
                            new BigDecimal(afterBalance).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP),
                            transactionNo);

                    // 发布余额变动事件（内存事件，在事务内OK）
                    eventPublisher.publishEvent(new WalletBalanceChangedEvent(
                            this, wallet.getWalletNo(), customerNo,
                            beforeBalance, afterBalance,
                            opType == BalanceOperationType.ADD ? amountInCents : -amountInCents,
                            transactionType));

                    // 发送余额变动MQ消息到业务系统 — 在事务提交后发送，防止脏消息
                    final BalanceChangedMessage mqMsg = new BalanceChangedMessage();
                    mqMsg.setCustomerNo(customerNo);
                    mqMsg.setWalletNo(wallet.getWalletNo());
                    mqMsg.setBizType(bizType);
                    mqMsg.setBeforeBalanceFen(beforeBalance);
                    mqMsg.setAfterBalanceFen(afterBalance);
                    mqMsg.setChangeAmountFen(opType == BalanceOperationType.ADD ? amountInCents : -amountInCents);
                    mqMsg.setTransactionType(transactionType);

                    TransactionSynchronizationUtils.afterCommit(() -> paymentEventProducer.sendBalanceChanged(mqMsg));

                    return opType == BalanceOperationType.ADD ? null : true;
                }

                log.warn("{}余额乐观锁冲突，重试中：用户={}, 当前重试次数={}",
                        opType == BalanceOperationType.ADD ? "增加" : "扣减", customerNo, retryCount + 1);

            } catch (DuplicateKeyException dke) {
                // 数据库 UNIQUE(reference_no) 约束命中，说明同一笔交易已由并发请求处理完成
                log.info("{}余额幂等命中（DuplicateKey）：用户={}, 关联单号={}",
                        opType == BalanceOperationType.ADD ? "增加" : "扣减", customerNo, referenceNo);
                return opType == BalanceOperationType.ADD ? null : true;
            } catch (Exception e) {
                log.error("{}余额异常：用户={}, 错误={}",
                        opType == BalanceOperationType.ADD ? "增加" : "扣减", customerNo, e.getMessage(), e);
                throw e;
            }

            retryCount++;
            wallet = walletMapper.selectByWalletNo(wallet.getWalletNo());
            if (wallet == null) {
                throw new BusinessException("钱包不存在");
            }

            if (opType == BalanceOperationType.DEDUCT && !wallet.hasEnoughBalance(amountInCents)) {
                log.warn("重试时发现余额不足：用户={}, 当前余额={}, 扣减金额={}",
                        customerNo, wallet.getBalanceInYuan(), amount);
                return false;
            }

            beforeBalance = wallet.getBalance();
        }

        if (opType == BalanceOperationType.ADD) {
            throw new BusinessException("增加余额失败，达到最大重试次数");
        }
        log.error("扣减余额失败，达到最大重试次数：用户={}", customerNo);
        return false;
    }
}
