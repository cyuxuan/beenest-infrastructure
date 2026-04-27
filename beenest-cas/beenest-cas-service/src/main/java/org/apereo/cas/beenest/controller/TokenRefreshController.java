package org.apereo.cas.beenest.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apereo.cas.beenest.common.response.R;
import org.apereo.cas.beenest.dto.TokenRefreshRequestDTO;
import org.apereo.cas.beenest.dto.TokenResponseDTO;
import org.apereo.cas.beenest.service.CasNativeLoginService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一 Token 续期控制器。
 * <p>
 * 续期入口仍然保留在这里，但真正的 Token 生命周期处理已经下沉到共享服务，
 * 避免小程序和 refresh 场景各自维护一套重复逻辑。
 */
@Slf4j
@RestController
@RequestMapping
@RequiredArgsConstructor
public class TokenRefreshController {

    private final CasNativeLoginService nativeLoginService;

    /**
     * 统一 refresh 端点。
     *
     * @param request 刷新请求
     * @param httpRequest 当前 HTTP 请求
     * @return 新的 accessToken / refreshToken
     */
    @PostMapping("/refresh")
    public R<TokenResponseDTO> refresh(@RequestBody TokenRefreshRequestDTO request,
                                       HttpServletRequest httpRequest) {
        return nativeLoginService.refresh(request.getRefreshToken(), httpRequest);
    }
}
