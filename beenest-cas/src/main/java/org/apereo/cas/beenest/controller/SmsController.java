package org.apereo.cas.beenest.controller;

import org.apereo.cas.beenest.common.exception.BusinessException;
import org.apereo.cas.beenest.common.response.R;
import org.apereo.cas.beenest.dto.SmsSendDTO;
import org.apereo.cas.beenest.dto.SmsSendResultDTO;
import org.apereo.cas.beenest.service.SmsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 短信验证码控制器
 * <p>
 * 发送短信验证码。限流在 SmsService 中实现。
 * 使用强类型 DTO 接收请求，自动校验手机号格式。
 */
@Slf4j
@RestController
@RequestMapping("/sms")
@RequiredArgsConstructor
public class SmsController {

    private final SmsService smsService;

    /**
     * 发送短信验证码
     *
     * @param dto 包含手机号的请求 DTO（自动校验格式）
     * @return 发送结果
     */
    @PostMapping("/send")
    public R<SmsSendResultDTO> send(@Valid @RequestBody SmsSendDTO dto) {
        return sendOtp(dto.getPhone());
    }

    /**
     * 发送短信验证码（GET 版本，保留给兼容调用）
     */
    @GetMapping("/send")
    public R<SmsSendResultDTO> sendByGet(@RequestParam String phone) {
        return sendOtp(phone);
    }

    private R<SmsSendResultDTO> sendOtp(String phone) {
        try {
            smsService.sendOtp(phone);
            SmsSendResultDTO data = new SmsSendResultDTO();
            data.setPhone(phone);
            return R.ok(data);
        } catch (BusinessException e) {
            return R.fail(e.getCode(), e.getMessage());
        }
    }
}
