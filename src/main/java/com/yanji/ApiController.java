package com.yanji;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/bird/v1")
public class ApiController {
    private final RegistrationService service;
    public ApiController(RegistrationService service) { this.service = service; }
    public record Credentials(@Pattern(regexp = "[a-z0-9_]{4,32}") @jakarta.validation.constraints.NotNull String username,
                              @Size(min = 10, max = 72) @jakarta.validation.constraints.NotNull String password) {}
    public record Claim(String code, @jakarta.validation.constraints.NotNull String name) {}
    public record Adoption(@jakarta.validation.constraints.NotNull String requestId, @jakarta.validation.constraints.NotNull String name) {}

    @GetMapping("/health") public Map<String,String> health() { return Map.of("status", "ok"); }
    @PostMapping("/auth/register") @ResponseStatus(HttpStatus.CREATED)
    public RegistrationService.Session register(@Valid @RequestBody Credentials body) { return service.register(body.username(), body.password()); }
    @PostMapping("/auth/login")
    public RegistrationService.Session login(@Valid @RequestBody Credentials body) { return service.login(body.username(), body.password()); }
    @PostMapping("/auth/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader(value = "Authorization", required = false) String auth) { service.logout(auth); }
    @GetMapping("/auth/session")
    public RegistrationService.CurrentSession session(@RequestHeader(value="Authorization",required=false) String auth) { return service.session(auth); }
    @GetMapping("/me/birds/{id}/binding")
    public RegistrationService.Binding binding(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String id) { return service.binding(auth,id); }
    @PostMapping("/birds/{id}/adopt")
    public RegistrationService.Binding adopt(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String id,@Valid @RequestBody Adoption body) { return service.adopt(auth,id,body.requestId(),body.name()); }
    @GetMapping("/birds/{id}")
    public RegistrationService.Bird find(@PathVariable String id) { return service.find(id); }
    @GetMapping("/birds/by-code/{birdCode}")
    public RegistrationService.Bird findByCode(@PathVariable String birdCode) { return service.findByCode(birdCode); }
    @GetMapping("/me/birds")
    public List<RegistrationService.Bird> mine(@RequestHeader(value = "Authorization", required = false) String auth) { return service.mine(auth); }
    @PostMapping("/birds/{id}/claim")
    public RegistrationService.Bird claim(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable String id, @Valid @RequestBody Claim body) {
        return service.claim(auth, id, body.code(), body.name());
    }
}
