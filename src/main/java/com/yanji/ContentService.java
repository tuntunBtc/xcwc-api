package com.yanji;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ContentService {
    private final JdbcTemplate db;private final ObjectMapper json;private final AdminAccess access;private final RegistrationService keys;private final Path directory;
    public ContentService(JdbcTemplate db,ObjectMapper json,AdminAccess access,RegistrationService keys,@Value("${management-content.directory:./data/content}") String directory){this.db=db;this.json=json;this.access=access;this.keys=keys;this.directory=Path.of(directory).toAbsolutePath().normalize();}
    static RegistrationService.Failure failure(HttpStatus status,String code){return new RegistrationService.Failure(status,code,code);}
    private String bytesDigest(byte[] data){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    JsonNode parse(String s){try{return json.readTree(s);}catch(Exception e){throw new IllegalStateException(e);}}
    String encode(Object o){try{return json.writeValueAsString(o);}catch(Exception e){throw new IllegalStateException(e);}}
    void audit(AdminAccess.Actor a,String op,String target){db.update("INSERT INTO sys_oper_log(title,business_type,oper_name,oper_param,status,oper_time) VALUES(?,2,?,?,0,CURRENT_TIMESTAMP)",op,a.username(),target);}
    public Map<String,Object> draft(){access.require("content:read");var r=db.queryForMap("SELECT * FROM content_workspace WHERE id=1");Map<String,Object> v=new LinkedHashMap<>();v.put("version",r.get("version").toString());v.put("publishedId",r.get("published_id"));v.put("content",parse((String)r.get("draft")));return v;}
    @Transactional public Map<String,Object> save(long expected,JsonNode document){
        var a=access.require("content:edit");String encoded=encode(document);
        if(encoded.length()>2000000)throw ContentRules.fail("CONTENT_TOO_LARGE");
        ContentRules.fields(document,"schemaVersion","characters","appearances","accessories","pages","products"); ContentRules.integer(document,"schemaVersion",1,1); for(String group:List.of("characters","appearances","accessories","pages","products"))ContentRules.index(document,group,5000);
        if(db.update("UPDATE content_workspace SET draft=?,version=version+1 WHERE id=1 AND version=?",encoded,expected)!=1)throw failure(HttpStatus.CONFLICT,"CONTENT_VERSION_CONFLICT");
        audit(a,"SAVE_DRAFT",Long.toString(expected+1));return Map.of("version",Long.toString(expected+1),"content",document);
    }
    private Map<String,Object> lock(){return db.queryForMap("SELECT * FROM content_workspace WHERE id=1 FOR UPDATE");}
    public Map<String,Object> validation(){access.require("content:read");JsonNode doc=parse(db.queryForObject("SELECT draft FROM content_workspace WHERE id=1",String.class));validateFiles(doc);return Map.of("valid",true,"assetCount",ContentRules.validate(doc).size());}
    private Set<String> validateFiles(JsonNode doc){
        Set<String> refs=ContentRules.validate(doc);
        for(String id:refs){var a=assetRow(id);Path f=path(id);try{if(!Files.isRegularFile(f)||Files.size(f)!=((Number)a.get("size_bytes")).longValue()||!bytesDigest(Files.readAllBytes(f)).equals(a.get("sha256")))throw failure(HttpStatus.CONFLICT,"ASSET_UNAVAILABLE");}catch(IOException e){throw failure(HttpStatus.CONFLICT,"ASSET_UNAVAILABLE");}}
        for(var look:doc.path("appearances"))if(look.hasNonNull("animation")){var anim=look.get("animation");var row=assetRow(anim.get("assetId").asText());if(((Number)row.get("width")).intValue()%anim.get("columns").asInt()!=0||((Number)row.get("height")).intValue()%anim.get("rows").asInt()!=0)throw ContentRules.fail("INVALID_ANIMATION_GRID");}
        return refs;
    }
    private byte[] requestKey(String id){if(id==null||!id.matches("[A-Za-z0-9_-]{16,64}"))throw ContentRules.fail("INVALID_REQUEST_ID");return id.getBytes(StandardCharsets.US_ASCII);}
    private Map<String,Object> replay(long actor,String request,String hash){var rows=db.queryForList("SELECT payload_hash,result_json FROM content_command WHERE actor_id=? AND request_id=?",actor,requestKey(request));if(rows.isEmpty())return null;if(!rows.getFirst().get("payload_hash").equals(hash))throw failure(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT");var r=new LinkedHashMap<String,Object>();parse((String)rows.getFirst().get("result_json")).fields().forEachRemaining(e->r.put(e.getKey(),json.convertValue(e.getValue(),Object.class)));r.put("replayed",true);return r;}
    @Transactional(isolation=Isolation.READ_COMMITTED) public Map<String,Object> publish(long expected,String request){return change(expected,request,null);}
    @Transactional(isolation=Isolation.READ_COMMITTED) public Map<String,Object> rollback(long expected,String request,String releaseId){return change(expected,request,releaseId);}
    private Map<String,Object> change(long expected,String request,String target){
        var a=access.require("content:publish");var workspace=lock();String hash=RegistrationService.digest(encode(Arrays.asList(expected,target)));var old=replay(a.id(),request,hash);if(old!=null)return old;
        if(((Number)workspace.get("version")).longValue()!=expected)throw failure(HttpStatus.CONFLICT,"CONTENT_VERSION_CONFLICT");
        JsonNode doc=target==null?parse((String)workspace.get("draft")):release(target);Set<String> refs=validateFiles(doc);
        // New publications may not silently remove existing role/wardrobe identities or compatibility.
        if(target==null){for(String previous:db.queryForList("SELECT payload FROM content_release",String.class))ContentRules.preserve(parse(previous),doc);}
        String id=keys.randomKey(16);db.update("INSERT INTO content_release(id,workspace_version,payload,actor_id,created_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP)",id,expected,encode(doc),a.id());
        for(String asset:refs)db.update("INSERT INTO content_release_asset(release_id,asset_id) VALUES(?,?)",id,asset);
        db.update("UPDATE content_workspace SET published_id=?,version=version+1 WHERE id=1",id);
        var result=Map.<String,Object>of("releaseId",id,"version",Long.toString(expected+1),"replayed",false);
        db.update("INSERT INTO content_command(actor_id,request_id,payload_hash,result_json) VALUES(?,?,?,?)",a.id(),requestKey(request),hash,encode(result));audit(a,target==null?"PUBLISH":"ROLLBACK",id);return result;
    }
    JsonNode release(String id){var rows=db.queryForList("SELECT payload FROM content_release WHERE id=?",String.class,id);if(rows.isEmpty())throw failure(HttpStatus.NOT_FOUND,"RELEASE_NOT_FOUND");return parse(rows.getFirst());}
    public String publishedId(){return db.queryForObject("SELECT published_id FROM content_workspace WHERE id=1",String.class);}
    JsonNode published(){String id=publishedId();return id==null?parse("{\"schemaVersion\":1,\"characters\":[],\"appearances\":[],\"accessories\":[],\"pages\":[],\"products\":[]}"):release(id);}
    private List<Map<String,Object>> publishedAssets(String releaseId){if(releaseId==null)return List.of();var rows=db.queryForList("SELECT a.id,a.mime,a.width,a.height,a.size_bytes,a.sha256 FROM content_asset a JOIN content_release_asset r ON r.asset_id=a.id WHERE r.release_id=? ORDER BY a.id",releaseId);var result=new ArrayList<Map<String,Object>>();for(var row:rows)result.add(Map.of("id",row.get("id"),"mime",row.get("mime"),"width",row.get("width"),"height",row.get("height"),"size_bytes",row.get("size_bytes"),"sha256",row.get("sha256"),"url","/api/content/assets/"+row.get("id")));return result;}
    private Map<String,Object> capabilities(){return Map.of("schemaVersion",1,"imageFormats",List.of("image/png","image/jpeg"),"animationFormats",List.of("sprite-grid"),"supportsAudio",false,"coordinateScale",1000);}
    public Map<String,Object> publicConfig(){String id=publishedId();return Map.of("releaseId",id==null?"":id,"content",id==null?published():release(id),"assets",publishedAssets(id),"capabilities",capabilities());}
    public Map<String,Object> publicCharacter(String id){String releaseId=publishedId();var doc=releaseId==null?published():release(releaseId);JsonNode character=ContentRules.index(doc,"characters",500).get(id);if(character==null)throw failure(HttpStatus.NOT_FOUND,"CHARACTER_NOT_FOUND");return Map.of("character",character,"appearances",filter(doc,"appearances","characterId",id),"pages",doc.get("pages"),"releaseId",Objects.toString(releaseId,""),"assets",publishedAssets(releaseId),"capabilities",capabilities());}
    private List<JsonNode> filter(JsonNode doc,String group,String key,String id){List<JsonNode> list=new ArrayList<>();for(var n:doc.path(group))if(n.path(key).asText().equals(id))list.add(n);return list;}
    public List<Map<String,Object>> releases(long before){access.require("content:read");return db.queryForList("SELECT id,workspace_version,actor_id,created_at FROM content_release WHERE workspace_version<? ORDER BY workspace_version DESC LIMIT 50",before);}
    public List<Map<String,Object>> auditLog(long before){access.require("content:audit");return db.queryForList("SELECT oper_id,title,oper_name,oper_param,oper_time FROM sys_oper_log WHERE oper_id<? ORDER BY oper_id DESC LIMIT 50",before);}
    private Path path(String id){if(id==null||!id.matches("[0-9a-f]{32}"))throw ContentRules.fail("INVALID_ASSET_ID");return directory.resolve(id);}
    Map<String,Object> assetRow(String id){var rows=db.queryForList("SELECT * FROM content_asset WHERE id=?",id);if(rows.isEmpty())throw failure(HttpStatus.NOT_FOUND,"ASSET_NOT_FOUND");return rows.getFirst();}
    public List<Map<String,Object>> assets(long before){access.require("content:read");return db.queryForList("SELECT id,mime,size_bytes,width,height,original_name,license_note,created_at FROM content_asset ORDER BY created_at DESC,id LIMIT 100 OFFSET ?",Math.min(Math.max(before,0),100000));}
    @Transactional public Map<String,Object> upload(MultipartFile file,String license) throws IOException {
        var a=access.require("content:edit");if(file.getSize()==0||file.getSize()>10*1024*1024)throw ContentRules.fail("ASSET_SIZE_INVALID");
        if(license==null||license.isBlank()||license.length()>500)throw ContentRules.fail("ASSET_LICENSE_REQUIRED");
        byte[] data=file.getBytes();String mime;
        if(data.length>8&&data[0]==(byte)137&&data[1]==80&&data[2]==78&&data[3]==71)mime="image/png";
        else if(data.length>3&&data[0]==(byte)255&&data[1]==(byte)216&&data[2]==(byte)255)mime="image/jpeg";
        else throw ContentRules.fail("ASSET_FORMAT_UNSUPPORTED");
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(data))){var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw ContentRules.fail("ASSET_CORRUPT");var reader=readers.next();try{reader.setInput(input);int w=reader.getWidth(0),h=reader.getHeight(0);if(w<1||h<1||w>8192||h>8192||(long)w*h>24000000)throw ContentRules.fail("ASSET_DIMENSIONS_INVALID");reader.read(0);
            String id=keys.randomKey(16);Files.createDirectories(directory.resolve("originals"));Files.write(directory.resolve("originals").resolve(id),data,StandardOpenOption.CREATE_NEW);Files.write(path(id),data,StandardOpenOption.CREATE_NEW);
            String filename=Objects.toString(file.getOriginalFilename(),"image").replaceAll("[^\\p{L}\\p{N}._ -]","_");if(filename.length()>160)filename=filename.substring(0,160);
            db.update("INSERT INTO content_asset(id,sha256,mime,size_bytes,width,height,original_name,license_note,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",id,bytesDigest(data),mime,data.length,w,h,filename,license,a.id());audit(a,"UPLOAD_ASSET",id);return Map.of("assetId",id,"mime",mime,"width",w,"height",h,"size",data.length);
        }finally{reader.dispose();}}
    }
    public byte[] readAsset(String id,boolean management,String preview){
        if(management)access.require("content:read");
        else if(preview!=null){if(!ContentRules.validate(preview(preview)).contains(id))throw failure(HttpStatus.NOT_FOUND,"ASSET_NOT_FOUND");}
        else if(db.queryForObject("SELECT COUNT(*) FROM content_release_asset WHERE asset_id=?",Integer.class,id)==0)throw failure(HttpStatus.NOT_FOUND,"ASSET_NOT_PUBLISHED");
        assetRow(id);try{return Files.readAllBytes(path(id));}catch(IOException e){throw failure(HttpStatus.NOT_FOUND,"ASSET_UNAVAILABLE");}
    }
    public Map<String,Object> dependencies(String id){access.require("content:read");assetRow(id);String draft=db.queryForObject("SELECT draft FROM content_workspace WHERE id=1",String.class);return Map.of("assetId",id,"draftReferenced",containsValue(parse(draft),id),"releaseIds",db.queryForList("SELECT release_id FROM content_release_asset WHERE asset_id=?",String.class,id));}
    private boolean containsValue(JsonNode node,String id){if(node.isTextual())return node.asText().equals(id);for(JsonNode child:node)if(containsValue(child,id))return true;return false;}
    @Transactional public void deleteUnusedAsset(String id){var a=access.require("content:edit");var work=lock();assetRow(id);
        if(containsValue(parse((String)work.get("draft")),id)||db.queryForObject("SELECT COUNT(*) FROM content_release_asset WHERE asset_id=?",Integer.class,id)>0)throw failure(HttpStatus.CONFLICT,"ASSET_STILL_REFERENCED");
        for(String p:db.queryForList("SELECT payload FROM content_preview WHERE expires_at>CURRENT_TIMESTAMP",String.class))if(containsValue(parse(p),id))throw failure(HttpStatus.CONFLICT,"ASSET_STILL_REFERENCED");
        // Delete metadata transactionally. Bytes remain quarantined for operator recovery/retention cleanup.
        db.update("DELETE FROM content_asset WHERE id=?",id);audit(a,"QUARANTINE_UNUSED_ASSET",id);
    }
    @Transactional public Map<String,Object> previewToken(){var a=access.require("content:preview");String payload=db.queryForObject("SELECT draft FROM content_workspace WHERE id=1",String.class);validateFiles(parse(payload));String token=keys.randomKey(32);Instant expiry=Instant.now().plusSeconds(900);db.update("DELETE FROM content_preview WHERE expires_at<CURRENT_TIMESTAMP");db.update("INSERT INTO content_preview(token_hash,actor_id,payload,expires_at) VALUES(?,?,?,?)",RegistrationService.digest(token),a.id(),payload,Timestamp.from(expiry));audit(a,"CREATE_PREVIEW","15min");return Map.of("token",token,"expiresAt",expiry);}
    public JsonNode preview(String token){if(token==null||!token.matches("[0-9a-f]{64}"))throw failure(HttpStatus.UNAUTHORIZED,"PREVIEW_EXPIRED");var list=db.queryForList("SELECT p.payload FROM content_preview p JOIN sys_user u ON u.user_id=p.actor_id WHERE p.token_hash=? AND p.expires_at>CURRENT_TIMESTAMP AND u.status='0'",String.class,RegistrationService.digest(token));if(list.isEmpty())throw failure(HttpStatus.UNAUTHORIZED,"PREVIEW_EXPIRED");return parse(list.getFirst());}
    public List<Map<String,Object>> entities(String after){access.require("content:read");return db.queryForList("SELECT b.id,b.name,b.ip_code,b.active,c.character_id FROM bird b LEFT JOIN content_entity c ON c.bird_id=b.id WHERE b.id>? ORDER BY b.id LIMIT 50",after);}
    @Transactional public void mapEntity(String bird,String character){var a=access.require("content:edit");lock();if(!ContentRules.index(published(),"characters",500).containsKey(character))throw ContentRules.fail("PUBLISH_CHARACTER_FIRST");var birds=db.queryForList("SELECT id,ip_code FROM bird WHERE id=? FOR UPDATE",bird);if(birds.isEmpty())throw failure(HttpStatus.NOT_FOUND,"BIRD_NOT_FOUND");var previous=db.queryForList("SELECT character_id FROM content_entity WHERE bird_id=?",String.class,bird);
        if(!previous.isEmpty()&&!previous.getFirst().equals(character))throw failure(HttpStatus.CONFLICT,"ENTITY_CHARACTER_IMMUTABLE");
        if(birds.getFirst().get("ip_code")!=null&&!birds.getFirst().get("ip_code").equals(ContentRules.index(published(),"characters",500).get(character).path("code").asText()))throw ContentRules.fail("EXISTING_IP_CODE_MISMATCH");
        if(previous.isEmpty())db.update("INSERT INTO content_entity(bird_id,character_id) VALUES(?,?)",bird,character);audit(a,"MAP_ENTITY",bird+":"+character);}
    public String characterForBird(String bird){var list=db.queryForList("SELECT character_id FROM content_entity WHERE bird_id=?",String.class,bird);return list.isEmpty()?null:list.getFirst();}
}
