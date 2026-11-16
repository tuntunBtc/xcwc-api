package com.yanji;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class WxPhoneAuthController {
    private final WxPhoneAuthService service;
    public WxPhoneAuthController(WxPhoneAuthService service) { this.service = service; }
    public record Body(@NotBlank @Size(max=512) String code, @NotBlank @Size(max=512) String phoneCode) {}
    @PostMapping("/wx-phone-login")
    public WxPhoneAuthService.Result login(@Valid @RequestBody Body body) { return service.login(body.code(), body.phoneCode()); }
}
