package com.yanji;

import java.io.IOException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class PublicUploadController {
    private final PublicUploadService uploads;
    public PublicUploadController(PublicUploadService uploads) {this.uploads=uploads;}
    @PostMapping(value="/admin/v1/oss/uploads", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PublicUploadService.Uploaded> upload(@RequestPart(value="file",required=false) MultipartFile file) throws IOException {
        var result = uploads.upload(file);
        return ResponseEntity.created(java.net.URI.create(result.url())).body(result);
    }
}
