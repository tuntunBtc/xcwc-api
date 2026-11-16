package com.yanji;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/me")
public class WardrobeController {
    private final WardrobeService wardrobe;
    public WardrobeController(WardrobeService wardrobe){this.wardrobe=wardrobe;}
    @GetMapping("/wardrobe") public Map<String,Object> catalog(@RequestHeader(value="Authorization",required=false) String auth){return wardrobe.catalog(auth);}
    @PostMapping("/wardrobe/acquisitions") public Map<String,Object> acquire(@RequestHeader(value="Authorization",required=false) String auth,@RequestBody WardrobeService.Acquire body){return wardrobe.acquire(auth,body);}
    @GetMapping("/wardrobe/operations/{requestId}") public Map<String,Object> operation(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String requestId){return wardrobe.operation(auth,requestId);}
    @GetMapping("/pets/{petId}/outfit") public Map<String,Object> outfit(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String petId){return wardrobe.outfit(auth,petId);}
    @PutMapping("/pets/{petId}/outfit") public Map<String,Object> wear(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String petId,@RequestBody WardrobeService.Wear body){return wardrobe.wear(auth,petId,body);}
}
