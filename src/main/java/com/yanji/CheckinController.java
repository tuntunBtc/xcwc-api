package com.yanji;

import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class CheckinController {
    private final CheckinService service;
    public CheckinController(CheckinService service){this.service=service;}
    @GetMapping("/checkin-catalog") public List<CheckinService.Character> catalog(){return service.catalog();}
    @PostMapping(value="/checkins",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public CheckinService.Work create(@RequestHeader(value="Authorization",required=false)String auth,
        @RequestPart("metadata")CheckinService.Submission metadata,@RequestPart("original")MultipartFile original,@RequestPart("image")MultipartFile image){
        return service.create(auth,metadata,original,image);
    }
    @GetMapping("/checkins/{id}") public CheckinService.Work get(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id){return service.find(auth,id);}
    @GetMapping("/me/checkins") public CheckinService.Page history(@RequestHeader(value="Authorization",required=false)String auth,
        @RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit){return service.history(auth,cursor,limit);}
    @GetMapping({"/checkins/{id}/image","/checkins/{id}/original"})
    public ResponseEntity<byte[]> image(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id,jakarta.servlet.http.HttpServletRequest request){
        var picture=service.image(auth,id,request.getRequestURI().endsWith("/original"));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(picture.type())).header("Content-Disposition","inline; filename=\"checkin.jpg\"").body(picture.bytes());
    }
}
