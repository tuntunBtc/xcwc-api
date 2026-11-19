package com.yanji;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bird/v1")
public class WxAuthController {
    private final WxAuthService service;
    public WxAuthController(WxAuthService service) { this.service = service; }

    public record LoginBody(@NotBlank @Size(max = 512) String code) {}

    @PostMapping("/auth/wx-login")
    public WxAuthService.LoginResult login(@Valid @RequestBody LoginBody body) {
        return service.login(body.code());
    }
}
