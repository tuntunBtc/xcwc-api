package com.yanji;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="nest-interactions.enabled",havingValue="true")
public class NestInteractionController {
    static final String ADMIN="/admin/v1/bird/brand-ips/{id}/nest-interactions";
    private final NestInteractionService service;
    private final PublicUploadReadController uploads;
    public NestInteractionController(NestInteractionService service,PublicUploadReadController uploads){this.service=service;this.uploads=uploads;}
    @RequestMapping(value="/test/bird/v1/oss/uploads/{filename}",method={RequestMethod.GET,RequestMethod.HEAD})
    public void testUpload(@PathVariable String filename,jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        uploads.download(filename,request,response);
    }
    public record Draft(Long expectedVersion,JsonNode actions){}
    public record Publish(Long expectedVersion){}
    @GetMapping(ADMIN) public Object draft(@PathVariable String id){return service.draft(id);}
    @PutMapping(ADMIN) public Object save(@PathVariable String id,@RequestBody Draft draft){return service.save(id,draft.expectedVersion(),draft.actions());}
    @PostMapping(ADMIN+"/publish") public Object publish(@PathVariable String id,@RequestBody Publish command){return service.publish(id,command.expectedVersion());}
    @GetMapping("/bird/v1/nest-interactions/{id}") public Object live(@PathVariable String id){return service.live(id);}
}
