package club.beenest.payment.security;

import club.beenest.payment.common.utils.AuthUtils;
import club.beenest.payment.common.utils.PhoneNumbers;
import club.beenest.payment.config.IamResourceServerProperties;
import club.beenest.payment.wallet.domain.entity.Wallet;
import club.beenest.payment.wallet.domain.enums.WalletStatus;
import club.beenest.payment.wallet.mapper.WalletMapper;
import club.beenest.payment.wallet.service.IWalletService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 本地用户准入过滤器。
 * <p>
 * 支付服务没有独立用户表，用户身份通过钱包（{@code ds_wallet}）的 {@code customer_no}
 * 关联 IAM 令牌中的<b>手机号</b>——手机号是 IAM 文档指定的跨渠道归并锚点，同一自然人用
 * 微信、支付宝、后台建号登录都会落到同一个钱包。IAM 负责"你是谁、能不能进这个应用"，
 * 本过滤器负责"这个人在支付侧有没有可用档案"——两者职责不重叠，缺一不可。
 *
 * <p>替代原 CAS 客户端提供的访问控制 SPI（{@code CasUserAccessControlService}），
 * 语义保持一致：
 * <ul>
 *   <li>首次访问且本地无档案 → 自动开通钱包（{@code iam.resource-server.auto-provision-wallet}），
 *       同时把令牌里的昵称/手机号写入档案</li>
 *   <li>钱包状态非 ACTIVE（冻结/关闭）→ 直接 403</li>
 * </ul>
 *
 * <p>仅作用于 C 端路径（{@code /api/wallet/**}、{@code /api/customer/**}）：
 * 管理端接口按角色鉴权，不应因"没有钱包"被拦；内部接口与支付回调走各自独立的鉴权链路。</p>
 *
 * <p>过滤器顺序排在 Spring Security 过滤链之后，此时 {@code SecurityContext} 已由
 * Bearer Token 认证填充。日志中手机号一律脱敏输出。</p>
 *
 * @since 2026-10-29
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 10)
public class PaymentUserAdmissionFilter extends OncePerRequestFilter {

    /** 需要做本地档案准入的 C 端路径前缀 */
    private static final List<String> CUSTOMER_PATH_PREFIXES = List.of("/api/wallet/", "/api/customer/");

    private final IamResourceServerProperties iamProperties;
    private final IWalletService walletService;
    private final WalletMapper walletMapper;

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        if (!iamProperties.isEnabled()) {
            return true;
        }
        String path = request.getRequestURI();
        return CUSTOMER_PATH_PREFIXES.stream().noneMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        // 1. 未认证请求（如支付回调）不做本地准入，交由各自链路处理
        if (!isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        // 2. 解析钱包主键（IAM 令牌中的手机号）
        String customerNo = AuthUtils.getCurrentUserId();
        if (!StringUtils.hasText(customerNo)) {
            log.warn("本地准入失败: 令牌缺少可用手机号, uri={}", request.getRequestURI());
            reject(response, "当前账号未绑定手机号，请先完成手机号绑定");
            return;
        }

        // 3. 查询本地钱包档案
        Wallet wallet = walletMapper.selectByCustomerNo(customerNo);
        if (wallet == null) {
            if (!iamProperties.isAutoProvisionWallet()) {
                log.warn("本地准入失败: 本地无档案且未开启自动开通, customer={}, uri={}",
                        PhoneNumbers.mask(customerNo), request.getRequestURI());
                reject(response, "本地用户不存在");
                return;
            }
            wallet = walletService.getOrCreateWallet(customerNo, iamProperties.getWalletBizType());
            if (wallet == null) {
                log.error("本地准入失败: 自动开通钱包未返回档案, customer={}", PhoneNumbers.mask(customerNo));
                reject(response, "本地用户开通失败，请稍后重试");
                return;
            }
            log.info("本地准入: 首次访问自动开通钱包, customer={}, walletNo={}",
                    PhoneNumbers.mask(customerNo), wallet.getWalletNo());
            syncCustomerProfile(customerNo);
        }

        // 4. 钱包状态校验：冻结/关闭的用户不允许访问 C 端接口
        if (iamProperties.isRejectInactiveWallet()
                && !WalletStatus.ACTIVE.getCode().equals(wallet.getStatus())) {
            log.warn("本地准入失败: 钱包状态异常, customer={}, status={}, uri={}",
                    PhoneNumbers.mask(customerNo), wallet.getStatus(), request.getRequestURI());
            reject(response, "账户状态异常，请联系客服");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 把令牌里的展示信息落到钱包档案。
     * <p>建户时令牌里已有昵称/手机号，直接写入可省掉后续的回查；两者都没有时不做无意义更新。</p>
     *
     * @param customerNo 钱包主键（归一化手机号）
     */
    private void syncCustomerProfile(String customerNo) {
        String customerName = AuthUtils.getCurrentUsername();
        String customerPhone = AuthUtils.getCurrentUserId();
        if (!StringUtils.hasText(customerName) && !StringUtils.hasText(customerPhone)) {
            return;
        }
        try {
            walletMapper.updateCustomerProfileByCustomerNo(customerNo, customerName, customerPhone);
        } catch (Exception e) {
            // 画像同步失败不影响本次请求，档案本身已建好
            log.warn("本地准入: 同步用户画像失败, customer={}, error={}",
                    PhoneNumbers.mask(customerNo), e.getMessage());
        }
    }

    /**
     * 判断当前请求是否已认证（排除匿名认证）。
     *
     * @return true 表示已认证
     */
    private boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    /**
     * 输出拒绝响应。
     *
     * @param response HTTP 响应
     * @param message  提示信息
     * @throws IOException 写响应失败时抛出
     */
    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"message\":\"" + message + "\",\"data\":null}");
    }
}
