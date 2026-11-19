package com.yanji;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/admin/v1/bird/brand-ips")
public class BrandIpController {
    private final BrandIpService service;
    public BrandIpController(BrandIpService service){this.service=service;}
    public record Save(String expectedVersion,JsonNode content){}
    public record Action(String action,String draftVersion,String stateVersion,String requestId,String releaseId){}
    @GetMapping public Object list(@RequestParam(defaultValue="") String query,@RequestParam(defaultValue="") String city,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return service.list(query,city,status,page,size);}
    @PostMapping public ResponseEntity<?> create(@RequestBody JsonNode content){return ResponseEntity.status(201).body(service.create(content));}
    @GetMapping("/{id}") public Object detail(@PathVariable String id){return service.detail(id);}
    @PutMapping("/{id}") public Object save(@PathVariable String id,@RequestBody Save input){return service.save(id,input.expectedVersion(),input.content());}
    @PostMapping("/{id}/actions") public Object action(@PathVariable String id,@RequestBody Action input){return service.action(id,input.action(),input.draftVersion(),input.stateVersion(),input.requestId(),input.releaseId());}
    @GetMapping("/{id}/history") public Object history(@PathVariable String id){return service.history(id);}
    @PostMapping("/{id}/preview") public Object preview(@PathVariable String id){return service.preview(id);}
    @DeleteMapping("/{id}") public ResponseEntity<?> delete(@PathVariable String id,@RequestParam String expectedVersion){service.delete(id,expectedVersion);return ResponseEntity.noContent().build();}
}
