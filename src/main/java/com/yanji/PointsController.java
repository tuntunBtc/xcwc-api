package com.yanji;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class PointsController {
    private final PointsService points;
    public PointsController(PointsService points) { this.points = points; }
    public record Request(@NotNull @Pattern(regexp="[A-Za-z0-9_-]{16,64}") String requestId) {}
    public record Equip(@Pattern(regexp="[a-z0-9_]{1,32}") String skinId) {}
    @GetMapping("/me/points")
    public PointsService.Wallet wallet(@RequestHeader(value="Authorization",required=false) String auth) { return points.wallet(auth); }
    @GetMapping("/me/points/ledger")
    public List<PointsService.Entry> ledger(@RequestHeader(value="Authorization",required=false) String auth,
        @RequestParam(defaultValue="9223372036854775807") long beforeId) { return points.history(auth,beforeId); }
    @GetMapping("/skins")
    public List<PointsService.Skin> skins(@RequestHeader(value="Authorization",required=false) String auth) { return points.skins(auth); }
    @PostMapping("/birds/{id}/pet")
    public PointsService.Change pet(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String id,@Valid @RequestBody Request body) { return points.pet(auth,id,body.requestId()); }
    @PostMapping("/skins/{id}/redeem")
    public PointsService.Change redeem(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String id,@Valid @RequestBody Request body) { return points.redeem(auth,id,body.requestId()); }
    @PutMapping("/birds/{id}/skin")
    public PointsService.Outfit equip(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String id,@Valid @RequestBody Equip body) { return points.equip(auth,id,body.skinId()); }
    @GetMapping("/me/outfits")
    public List<PointsService.Outfit> outfits(@RequestHeader(value="Authorization",required=false) String auth) { return points.outfits(auth); }
}
