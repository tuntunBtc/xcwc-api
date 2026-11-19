package com.yanji;

import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/bird/v1")
public class CheckinController {
    private final CheckinService service;
    private final ApiLinks links;
    public CheckinController(CheckinService service, ApiLinks links){this.links=links;this.service=service;}
    @GetMapping("/checkin-catalog") public List<CheckinService.Character> catalog(){return service.catalog();}
    @PostMapping(value="/checkins",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CheckinService.Work> create(@RequestHeader(value="Authorization",required=false)String auth,
        @RequestPart("metadata")CheckinService.Submission metadata,@RequestPart("original")MultipartFile original,@RequestPart("image")MultipartFile image){
        var result=service.createResponse(auth,metadata,original,image);
        return ResponseEntity.status(result.created()?201:200).location(java.net.URI.create(links.bird()+"/checkins/"+result.work().id())).body(withLinks(result.work()));
    }
    @GetMapping("/checkins/{id}") public CheckinService.Work get(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id){return withLinks(service.find(auth,id));}
    @GetMapping("/me/checkins") public CheckinService.Page history(@RequestHeader(value="Authorization",required=false)String auth,
        @RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit){var page = service.history(auth,cursor,limit);
        return new CheckinService.Page(page.items().stream().map(this::withLinks).toList(), page.nextCursor(), page.total());}
    private CheckinService.Work withLinks(CheckinService.Work work) {
        String base = links.bird() + "/checkins/" + work.id();
        return new CheckinService.Work(work.id(), work.status(), work.birdIds(), work.styleId(), work.label(),
            work.createdAt(), base + "/image", base + "/original", work.reward());
    }
    @GetMapping({"/checkins/{id}/image","/checkins/{id}/original"})
    public ResponseEntity<byte[]> image(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id,jakarta.servlet.http.HttpServletRequest request){
        var picture=service.image(auth,id,request.getRequestURI().endsWith("/original"));
        String filename="image/png".equals(picture.type())?"checkin.png":"checkin.jpg";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(picture.type())).header("Content-Disposition","inline; filename=\""+filename+"\"").body(picture.bytes());
    }
}
