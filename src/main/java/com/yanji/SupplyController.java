package com.yanji;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bird/v1/supplies")
public class SupplyController {
    private final SupplyService service;

    public SupplyController(SupplyService service) { this.service = service; }

    public record PreviewBody(
        @NotBlank @Size(max = 16384) String encryptedData,
        @NotBlank @Size(max = 64) String iv,
        @NotBlank @Pattern(regexp = "[0-9a-f]{32}") String installationId,
        @NotBlank @Size(max = 32) String birdCode) {}

    public record ClaimBody(
        @NotBlank @Size(max = 16384) String encryptedData,
        @NotBlank @Size(max = 64) String iv,
        @NotBlank @Pattern(regexp = "[0-9a-f]{32}") String installationId,
        @NotBlank @Size(max = 32) String birdCode,
        @NotBlank @Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}") String expectedDate) {}

    @PostMapping("/preview")
    public SupplyService.Result preview(@RequestHeader(value = "Authorization", required = false) String auth,
                                        @Valid @RequestBody PreviewBody body) {
        return service.preview(auth, body.encryptedData(), body.iv(), body.installationId(), body.birdCode());
    }

    @PostMapping("/claim")
    public SupplyService.Result claim(@RequestHeader(value = "Authorization", required = false) String auth,
                                      @Valid @RequestBody ClaimBody body) {
        return service.claim(auth, body.encryptedData(), body.iv(), body.installationId(), body.birdCode(), body.expectedDate());
    }
}
