package com.yanji;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.net.URI;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class AdminController {
    private final AdminIdentityService identity;private final ContentService content;
    private final ApiLinks links;
    public AdminController(AdminIdentityService identity,ContentService content, ApiLinks links){this.links=links;this.identity=identity;this.content=content;}
    public record Login(String username,String password){}
    public record Draft(long expectedVersion,JsonNode content){}
    public record Publish(long expectedVersion,String requestId,String releaseId){}
    public record Account(String username,String nickname,String password,List<Long> roles){}
    public record Roles(List<Long> roles,String status){}
    public record Password(String oldPassword,String newPassword){}
    public record Mapping(String characterId){}
    @PostMapping("/admin/v1/login") public Object login(@RequestBody Login c){return identity.login(c.username(),c.password());}
    @GetMapping("/admin/v1/captchaImage") public Object captcha(){return Map.of("code",200,"captchaEnabled",false);}
    @GetMapping("/admin/v1/getInfo") public Object info(){return identity.info();}
    @PostMapping("/admin/v1/logout") public Object logout(@RequestHeader(value="Authorization",required=false) String auth){identity.logout(auth);return Map.of("code",200);}
    @PutMapping("/admin/v1/password") public Object password(@RequestBody Password c){identity.changePassword(c.oldPassword(),c.newPassword());return Map.of("code",200);}
    @GetMapping("/admin/v1/getRouters") public Object routers(){
        identity.require("content:read");
        var brand=Map.of("path","/brand-ips","component","Layout","redirect","/brand-ips/list","name","BrandRoot","children",List.of(
            Map.of("path","list","name","BrandIps","component","operations/brandIps","meta",Map.of("title","品牌 IP 管理","icon","peoples")),
            Map.of("path",":id/expressions","name","BrandExpressions","component","operations/brandExpressions","hidden",true,"meta",Map.of("title","表情管理","activeMenu","/brand-ips/list"))));
        var legacy=Map.of("path","/operations","component","Layout","redirect","/operations/content","name","Operations","meta",Map.of("title","内容运营","icon","tree"),"children",List.of(Map.of("path","content","name","Content","component","operations/content","meta",Map.of("title","其他内容管理","icon","edit"))));
        return Map.of("code",200,"data",List.of(brand,legacy));
    }
    @GetMapping("/admin/v1/accounts") public Object accounts(){return identity.accounts();}
    @GetMapping("/admin/v1/accounts/{id}") public Object account(@PathVariable long id){return identity.account(id);}
    @PostMapping("/admin/v1/accounts") public ResponseEntity<?> account(@RequestBody Account c){long id=identity.createAccount(c.username(),c.nickname(),c.password(),c.roles());return ResponseEntity.created(URI.create(links.admin()+"/accounts/"+id)).body(Map.of("created",true,"id",Long.toString(id)));}
    @PutMapping("/admin/v1/accounts/{id}/roles") public Object roles(@PathVariable long id,@RequestBody Roles c){identity.setRoles(id,c.roles(),c.status());return Map.of("updated",true);}
    @GetMapping("/admin/v1/bird/content/draft") public Object draft(){return content.draft();}
    @PutMapping("/admin/v1/bird/content/draft") public Object draft(@RequestBody Draft c){return content.save(c.expectedVersion(),c.content());}
    @PostMapping("/admin/v1/bird/content/validate") public Object validate(){return content.validation();}
    @PostMapping("/admin/v1/bird/content/publish") public ResponseEntity<?> publish(@RequestBody Publish c){var r=content.publish(c.expectedVersion(),c.requestId());return ResponseEntity.status(Boolean.TRUE.equals(r.get("replayed"))?200:201).location(URI.create(links.birdAdmin()+"/content/releases/"+r.get("releaseId"))).body(r);}
    @PostMapping("/admin/v1/bird/content/rollback") public Object rollback(@RequestBody Publish c){if(c.releaseId()==null)throw ContentRules.fail("RELEASE_ID_REQUIRED");return content.rollback(c.expectedVersion(),c.requestId(),c.releaseId());}
    @GetMapping("/admin/v1/bird/content/releases") public Object releases(@RequestParam(defaultValue="9223372036854775807") long before){return content.releases(before);}
    @GetMapping("/admin/v1/bird/content/releases/{id}") public Object release(@PathVariable String id){identity.require("content:read");return content.release(id);}
    @GetMapping("/admin/v1/audit") public Object audit(@RequestParam(defaultValue="9223372036854775807") long before){return content.auditLog(before);}
    @GetMapping("/admin/v1/assets") public Object assets(@RequestParam(defaultValue="0") long offset,@RequestParam(defaultValue="image") String kind){return content.assets(offset,kind);}
    @PostMapping(value="/admin/v1/assets",consumes="multipart/form-data") public ResponseEntity<?> upload(@RequestPart MultipartFile file,@RequestParam(defaultValue="") String license) throws java.io.IOException {var r=content.upload(file,license);return ResponseEntity.created(URI.create(links.admin()+"/assets/"+r.get("assetId"))).body(r);}
    @GetMapping("/admin/v1/assets/{id}") public ResponseEntity<byte[]> asset(@PathVariable String id){byte[] b=content.readAsset(id,true,null);return ResponseEntity.ok().contentType(MediaType.parseMediaType((String)content.assetRow(id).get("mime"))).body(b);}
    @GetMapping("/admin/v1/assets/{id}/references") public Object references(@PathVariable String id){return content.dependencies(id);}
    @GetMapping("/admin/v1/assets/{id}/thumbnail") public ResponseEntity<byte[]> thumbnail(@PathVariable String id){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG).body(content.thumbnail(id));}
    @DeleteMapping("/admin/v1/assets/{id}") public ResponseEntity<?> deleteAsset(@PathVariable String id){content.deleteUnusedAsset(id);return ResponseEntity.noContent().build();}
    @PostMapping("/admin/v1/bird/content/preview-tokens") public Object preview(){return content.previewToken();}
    @GetMapping("/admin/v1/bird/entities") public Object entities(@RequestParam(defaultValue="") String after){return content.entities(after);}
    @PutMapping("/admin/v1/bird/entities/{birdId}/character") public Object mapping(@PathVariable String birdId,@RequestBody Mapping c){content.mapEntity(birdId,c.characterId());return Map.of("updated",true);}
}
