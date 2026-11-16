package com.yanji;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.net.URI;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminIdentityService identity;private final ContentService content;
    public AdminController(AdminIdentityService identity,ContentService content){this.identity=identity;this.content=content;}
    public record Login(String username,String password){}
    public record Draft(long expectedVersion,JsonNode content){}
    public record Publish(long expectedVersion,String requestId,String releaseId){}
    public record Account(String username,String nickname,String password,List<Long> roles){}
    public record Roles(List<Long> roles,String status){}
    public record Password(String oldPassword,String newPassword){}
    public record Mapping(String characterId){}
    @PostMapping("/login") public Object login(@RequestBody Login c){return identity.login(c.username(),c.password());}
    @GetMapping("/captchaImage") public Object captcha(){return Map.of("code",200,"captchaEnabled",false);}
    @GetMapping("/getInfo") public Object info(){return identity.info();}
    @PostMapping("/logout") public Object logout(@RequestHeader(value="Authorization",required=false) String auth){identity.logout(auth);return Map.of("code",200);}
    @PutMapping("/password") public Object password(@RequestBody Password c){identity.changePassword(c.oldPassword(),c.newPassword());return Map.of("code",200);}
    @GetMapping("/getRouters") public Object routers(){identity.require("content:read");return Map.of("code",200,"data",List.of(Map.of("path","/operations","component","Layout","redirect","/operations/content","name","Operations","meta",Map.of("title","内容运营","icon","tree"),"children",List.of(Map.of("path","content","name","Content","component","operations/content","meta",Map.of("title","内容管理","icon","edit"))))));}
    @GetMapping("/accounts") public Object accounts(){return identity.accounts();}
    @GetMapping("/accounts/{id}") public Object account(@PathVariable long id){return identity.account(id);}
    @PostMapping("/accounts") public ResponseEntity<?> account(@RequestBody Account c){long id=identity.createAccount(c.username(),c.nickname(),c.password(),c.roles());return ResponseEntity.created(URI.create("/api/admin/accounts/"+id)).body(Map.of("created",true,"id",Long.toString(id)));}
    @PutMapping("/accounts/{id}/roles") public Object roles(@PathVariable long id,@RequestBody Roles c){identity.setRoles(id,c.roles(),c.status());return Map.of("updated",true);}
    @GetMapping("/content/draft") public Object draft(){return content.draft();}
    @PutMapping("/content/draft") public Object draft(@RequestBody Draft c){return content.save(c.expectedVersion(),c.content());}
    @PostMapping("/content/validate") public Object validate(){return content.validation();}
    @PostMapping("/content/publish") public ResponseEntity<?> publish(@RequestBody Publish c){var r=content.publish(c.expectedVersion(),c.requestId());return ResponseEntity.status(Boolean.TRUE.equals(r.get("replayed"))?200:201).location(URI.create("/api/admin/content/releases/"+r.get("releaseId"))).body(r);}
    @PostMapping("/content/rollback") public Object rollback(@RequestBody Publish c){if(c.releaseId()==null)throw ContentRules.fail("RELEASE_ID_REQUIRED");return content.rollback(c.expectedVersion(),c.requestId(),c.releaseId());}
    @GetMapping("/content/releases") public Object releases(@RequestParam(defaultValue="9223372036854775807") long before){return content.releases(before);}
    @GetMapping("/content/releases/{id}") public Object release(@PathVariable String id){identity.require("content:read");return content.release(id);}
    @GetMapping("/audit") public Object audit(@RequestParam(defaultValue="9223372036854775807") long before){return content.auditLog(before);}
    @GetMapping("/assets") public Object assets(@RequestParam(defaultValue="0") long offset){return content.assets(offset);}
    @PostMapping(value="/assets",consumes="multipart/form-data") public ResponseEntity<?> upload(@RequestPart MultipartFile file,@RequestParam String license) throws java.io.IOException {var r=content.upload(file,license);return ResponseEntity.created(URI.create("/api/admin/assets/"+r.get("assetId"))).body(r);}
    @GetMapping("/assets/{id}") public ResponseEntity<byte[]> asset(@PathVariable String id){byte[] b=content.readAsset(id,true,null);return ResponseEntity.ok().contentType(MediaType.parseMediaType((String)content.assetRow(id).get("mime"))).body(b);}
    @GetMapping("/assets/{id}/references") public Object references(@PathVariable String id){return content.dependencies(id);}
    @DeleteMapping("/assets/{id}") public ResponseEntity<?> deleteAsset(@PathVariable String id){content.deleteUnusedAsset(id);return ResponseEntity.noContent().build();}
    @PostMapping("/content/preview-tokens") public Object preview(){return content.previewToken();}
    @GetMapping("/entities") public Object entities(@RequestParam(defaultValue="") String after){return content.entities(after);}
    @PutMapping("/entities/{birdId}/character") public Object mapping(@PathVariable String birdId,@RequestBody Mapping c){content.mapEntity(birdId,c.characterId());return Map.of("updated",true);}
}
