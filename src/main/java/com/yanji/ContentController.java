package com.yanji;

import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/content")
public class ContentController {
    private final ContentService content;
    public ContentController(ContentService content){this.content=content;}
    @GetMapping public Map<String,Object> config(){return content.publicConfig();}
    @GetMapping("/characters/{id}") public Map<String,Object> character(@PathVariable String id){return content.publicCharacter(id);}
    @GetMapping("/assets/{id}") public ResponseEntity<byte[]> asset(@PathVariable String id){byte[] bytes=content.readAsset(id,false,null);return ResponseEntity.ok().contentType(MediaType.parseMediaType((String)content.assetRow(id).get("mime"))).header("Cache-Control","public, max-age=31536000, immutable").body(bytes);}
    @GetMapping("/preview") public Object preview(@RequestHeader(value="X-Preview-Token",required=false) String token){return content.preview(token);}
    @GetMapping("/preview/assets/{id}") public ResponseEntity<byte[]> previewAsset(@PathVariable String id,@RequestHeader(value="X-Preview-Token",required=false) String token){if(token==null)throw ContentService.failure(HttpStatus.UNAUTHORIZED,"PREVIEW_EXPIRED");byte[] bytes=content.readAsset(id,false,token);return ResponseEntity.ok().contentType(MediaType.parseMediaType((String)content.assetRow(id).get("mime"))).header("Cache-Control","no-store").body(bytes);}
}
