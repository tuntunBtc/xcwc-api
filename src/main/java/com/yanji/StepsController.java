package com.yanji;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bird/v1")
public class StepsController {
    private final WxRunService service;
    public StepsController(WxRunService service) { this.service = service; }

    public record RedeemBody(
        @NotBlank @Size(max = 16384) String encryptedData,
        @NotBlank @Size(max = 64) String iv,
        @Pattern(regexp = "[A-Za-z0-9_-]{16,64}") @NotNull String requestId) {}

    public record PreviewV2Body(
        @NotBlank @Size(max = 16384) String encryptedData,
        @NotBlank @Size(max = 64) String iv) {}

    public record RedeemV2Body(
        @NotBlank @Size(max = 16384) String encryptedData,
        @NotBlank @Size(max = 64) String iv,
        @Pattern(regexp = "[A-Za-z0-9_-]{16,64}") @NotNull String requestId,
        @Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}") @NotNull String expectedDate) {}

    @PostMapping("/steps/redeem")
    public Map<String, Object> redeem(@RequestHeader(value = "Authorization", required = false) String auth,
                                      @Valid @RequestBody RedeemBody body) {
        WxRunService.RedeemResult r = service.redeem(auth, body.encryptedData(), body.iv(), body.requestId());
        return Map.of("granted", r.granted(), "steps", r.steps(), "date", r.date(), "claimed", r.claimed());
    }

    @PostMapping("/steps/preview-v2")
    public WxRunService.StepProgress previewV2(@RequestHeader(value = "Authorization", required = false) String auth,
                                               @Valid @RequestBody PreviewV2Body body) {
        return service.previewV2(auth, body.encryptedData(), body.iv());
    }

    @PostMapping("/steps/redeem-v2")
    public WxRunService.StepProgress redeemV2(@RequestHeader(value = "Authorization", required = false) String auth,
                                              @Valid @RequestBody RedeemV2Body body) {
        return service.redeemV2(auth, body.encryptedData(), body.iv(), body.requestId(), body.expectedDate());
    }
}
