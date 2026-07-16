package club.beenest.payment.admin.controller.internal;

import club.beenest.payment.common.Response;
import club.beenest.payment.shared.domain.entity.AppCredential;
import club.beenest.payment.shared.dto.CreateAppCredentialDTO;
import club.beenest.payment.shared.dto.UpdateAppCredentialDTO;
import club.beenest.payment.shared.service.AppCredentialService;
import club.beenest.payment.shared.vo.AppCredentialVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 应用凭证管理控制器
 *
 * <p>提供应用凭证的 CRUD 和密钥轮换功能。
 * 路径前缀 /internal/payment/app-credential，受 InternalApiFilter 保护。</p>
 *
 * <p><b>安全注意</b>：</p>
 * <ul>
 *   <li>GET 接口返回脱敏密钥（如 ****abcd），原始密钥不可查询</li>
 *   <li>创建和轮换接口返回明文密钥，仅此一次，务必通知调用方及时保存</li>
 * </ul>
 *
 * @author System
 * @since 2026-07-16
 */
@RestController
@RequestMapping("/internal/payment/app-credential")
@RequiredArgsConstructor
@Validated
@Slf4j
public class AppCredentialController {

    private final AppCredentialService appCredentialService;

    /**
     * 列表查询（密钥脱敏）
     */
    @GetMapping("/list")
    public Response<List<AppCredentialVO>> list() {
        List<AppCredential> credentials = appCredentialService.getAllActive();
        List<AppCredentialVO> voList = credentials.stream()
                .map(AppCredentialController::toMaskedVO)
                .toList();
        return Response.success(voList);
    }

    /**
     * 查询单个应用凭证（密钥脱敏）
     */
    @GetMapping("/{appId}")
    public Response<AppCredentialVO> getByAppId(@PathVariable String appId) {
        AppCredential credential = appCredentialService.getByAppId(appId);
        if (credential == null) {
            return Response.fail(404, "应用凭证不存在: " + appId);
        }
        return Response.success(toMaskedVO(credential));
    }

    /**
     * 创建应用凭证（返回原始密钥，仅此一次）
     */
    @PostMapping("/create")
    public Response<AppCredential> create(@Validated @RequestBody CreateAppCredentialDTO dto) {
        // 检查是否已存在
        if (appCredentialService.getByAppId(dto.getAppId()) != null) {
            return Response.fail(409, "应用凭证已存在: " + dto.getAppId());
        }

        log.info("创建应用凭证: appId={}, appName={}", dto.getAppId(), dto.getAppName());
        AppCredential result = appCredentialService.createApp(
                dto.getAppId(), dto.getAppName(),
                dto.getAllowedNetworks(), dto.getDescription(),
                "ADMIN");
        return Response.success(result);
    }

    /**
     * 更新应用信息（名称、IP白名单、描述）
     */
    @PostMapping("/update")
    public Response<Void> update(@RequestBody UpdateAppCredentialDTO dto) {
        if (dto.getAppId() == null || dto.getAppId().isBlank()) {
            return Response.fail(400, "appId 不能为空");
        }
        appCredentialService.updateApp(dto.getAppId(), dto.getAppName(),
                dto.getAllowedNetworks(), dto.getDescription(), "ADMIN");
        return Response.success();
    }

    /**
     * 轮换 app_secret（令牌认证 + HMAC 签名共用，返回新明文密钥，仅此一次）
     */
    @PostMapping("/rotate-secret/{appId}")
    public Response<String> rotateAppSecret(@PathVariable String appId) {
        if (appCredentialService.getByAppId(appId) == null) {
            return Response.fail(404, "应用凭证不存在: " + appId);
        }
        String newSecret = appCredentialService.rotateAppSecret(appId, "ADMIN");
        return Response.success(newSecret);
    }

    /**
     * 轮换 mq_secret（返回新明文密钥，仅此一次）
     */
    @PostMapping("/rotate-mq-secret/{appId}")
    public Response<String> rotateMqSecret(@PathVariable String appId) {
        if (appCredentialService.getByAppId(appId) == null) {
            return Response.fail(404, "应用凭证不存在: " + appId);
        }
        String newSecret = appCredentialService.rotateMqSecret(appId, "ADMIN");
        return Response.success(newSecret);
    }

    /**
     * 启用应用
     */
    @PostMapping("/enable/{appId}")
    public Response<Void> enable(@PathVariable String appId) {
        appCredentialService.enableApp(appId, "ADMIN");
        return Response.success();
    }

    /**
     * 禁用应用
     */
    @PostMapping("/disable/{appId}")
    public Response<Void> disable(@PathVariable String appId) {
        appCredentialService.disableApp(appId, "ADMIN");
        return Response.success();
    }

    // ==================== 内部方法 ====================

    /**
     * 将实体转换为脱敏 VO
     */
    private static AppCredentialVO toMaskedVO(AppCredential credential) {
        AppCredentialVO vo = new AppCredentialVO();
        vo.setId(credential.getId());
        vo.setAppId(credential.getAppId());
        vo.setAppName(credential.getAppName());
        vo.setAppSecret(AppCredentialService.maskSecret(credential.getAppSecret()));
        vo.setMqSecret(AppCredentialService.maskSecret(credential.getMqSecret()));
        vo.setAllowedNetworks(credential.getAllowedNetworks());
        vo.setStatus(credential.getStatus());
        vo.setDescription(credential.getDescription());
        vo.setCreatedBy(credential.getCreatedBy());
        vo.setCreateTime(credential.getCreateTime());
        vo.setUpdatedBy(credential.getUpdatedBy());
        vo.setUpdateTime(credential.getUpdateTime());
        return vo;
    }
}
