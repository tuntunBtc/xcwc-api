package com.yanji;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ContentService {
    private final JdbcTemplate db;private final ObjectMapper json;private final AdminAccess access;private final RegistrationService keys;private final ContentAssetStore storage;
    @org.springframework.beans.factory.annotation.Value("${management-content.animation-editing:false}")
    private boolean animationEditing;
    boolean animationEditing(){return animationEditing;}
    void requireAnimationEditing(){
        if(!animationEditing)throw failure(HttpStatus.CONFLICT,"ANIMATION_EDITING_DISABLED");
    }

    public ContentService(JdbcTemplate db,ObjectMapper json,AdminAccess access,RegistrationService keys,ContentAssetStore storage){this.db=db;this.json=json;this.access=access;this.keys=keys;this.storage=storage;}
    static RegistrationService.Failure failure(HttpStatus status,String code){return new RegistrationService.Failure(status,code,code);}
    private String bytesDigest(byte[] data){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    JsonNode parse(String s){try{return json.readTree(s);}catch(Exception e){throw new IllegalStateException(e);}}
    String encode(Object o){try{return json.writeValueAsString(o);}catch(Exception e){throw new IllegalStateException(e);}}
    void audit(AdminAccess.Actor a,String op,String target){db.update("INSERT INTO sys_oper_log(title,business_type,oper_name,oper_param,status,oper_time) VALUES(?,2,?,?,0,CURRENT_TIMESTAMP)",op,a.username(),target);}
    public Map<String,Object> draft(){access.require("content:read");var r=db.queryForMap("SELECT * FROM content_workspace WHERE id=1");Map<String,Object> v=new LinkedHashMap<>();v.put("version",r.get("version").toString());v.put("publishedId",r.get("published_id"));v.put("content",parse((String)r.get("draft")));return v;}
    @Transactional public Map<String,Object> save(long expected,JsonNode document){
        var a=access.require("content:edit");var current=lock();
        if(((Number)current.get("version")).longValue()!=expected)throw failure(HttpStatus.CONFLICT,"CONTENT_VERSION_CONFLICT");
        ContentRules.fields(document,"schemaVersion","characters","appearances","accessories","pages","products");
        document=BrandIpContent.pinManaged(document,parse((String)current.get("draft")),db.queryForList("SELECT character_id FROM brand_ip_revision",String.class));
        if(!animationEditing){
            var oldLooks=ContentRules.index(parse((String)current.get("draft")),"appearances",5000);
            var nextLooks=ContentRules.index(document,"appearances",5000);
            Set<String> ids=new HashSet<>(oldLooks.keySet());
            ids.addAll(nextLooks.keySet());
            for(String id:ids){
                var previous=oldLooks.containsKey(id)?oldLooks.get(id).path("animation"):null;
                var next=nextLooks.containsKey(id)?nextLooks.get(id).path("animation"):null;
                if(!Objects.equals(previous,next)&&(usesTimeline(previous)||usesTimeline(next)))requireAnimationEditing();
            }
        }
        String encoded=encode(document);
        if(encoded.length()>2000000)throw ContentRules.fail("CONTENT_TOO_LARGE");
        ContentRules.fields(document,"schemaVersion","characters","appearances","accessories","pages","products"); ContentRules.integer(document,"schemaVersion",1,1); for(String group:List.of("characters","appearances","accessories","pages","products"))ContentRules.index(document,group,5000);
        if(db.update("UPDATE content_workspace SET draft=?,version=version+1 WHERE id=1 AND version=?",encoded,expected)!=1)throw failure(HttpStatus.CONFLICT,"CONTENT_VERSION_CONFLICT");
        audit(a,"SAVE_DRAFT",Long.toString(expected+1));return Map.of("version",Long.toString(expected+1),"content",document);
    }
    private static boolean usesTimeline(JsonNode animation){
        return animation!=null&&(animation.has("durationMs")||animation.has("frameTimesMs")||animation.has("audioAssetId"));
    }
    private Map<String,Object> lock(){return db.queryForMap("SELECT * FROM content_workspace WHERE id=1 FOR UPDATE");}
    public Map<String,Object> validation(){access.require("content:read");JsonNode doc=parse(db.queryForObject("SELECT draft FROM content_workspace WHERE id=1",String.class));validateFiles(doc);return Map.of("valid",true,"assetCount",ContentRules.validate(doc).size());}
    private Set<String> validateFiles(JsonNode doc){
        Set<String> audio=new LinkedHashSet<>();
        Set<String> images=ContentRules.imageReferences(doc,audio);
        Set<String> refs=new LinkedHashSet<>(images);
        refs.addAll(audio);
        Map<String,Map<String,Object>> rows=new HashMap<>();
        Map<String,Long> durations=new HashMap<>();
        for(String id:refs){
            var row=assetRow(id);
            rows.put(id,row);
            boolean sound="audio/mp4".equals(row.get("mime"));
            if(sound!=audio.contains(id)||(sound&&images.contains(id)))throw ContentRules.fail("ASSET_TYPE_MISMATCH");
            try{
                byte[] data=storage.read(id);
                if(data.length!=((Number)row.get("size_bytes")).longValue()||!bytesDigest(data).equals(row.get("sha256")))
                    throw failure(HttpStatus.CONFLICT,"ASSET_UNAVAILABLE");
                if(sound)durations.put(id,AnimationAudio.durationMs(data,json));
            }catch(IOException e){throw failure(HttpStatus.CONFLICT,"ASSET_UNAVAILABLE");}
        }
        for(var look:doc.path("appearances")){
            if(!look.hasNonNull("animation"))continue;
            var anim=look.get("animation");
            var row=rows.get(anim.get("assetId").asText());
            if(((Number)row.get("width")).intValue()%anim.get("columns").asInt()!=0
                    ||((Number)row.get("height")).intValue()%anim.get("rows").asInt()!=0)
                throw ContentRules.fail("INVALID_ANIMATION_GRID");
            if(anim.hasNonNull("audioAssetId")&&Math.abs(durations.get(anim.get("audioAssetId").asText())-anim.get("durationMs").intValue())>100)
                throw ContentRules.fail("ANIMATION_AUDIO_DURATION_MISMATCH");
        }
        return refs;
    }
    byte[] requestKey(String id){if(id==null||!id.matches("[A-Za-z0-9_-]{16,64}"))throw ContentRules.fail("INVALID_REQUEST_ID");return id.getBytes(StandardCharsets.US_ASCII);}
    Map<String,Object> replay(long actor,String request,String hash){var rows=db.queryForList("SELECT payload_hash,result_json FROM content_command WHERE actor_id=? AND request_id=?",actor,requestKey(request));if(rows.isEmpty())return null;if(!rows.getFirst().get("payload_hash").equals(hash))throw failure(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT");var r=new LinkedHashMap<String,Object>();parse((String)rows.getFirst().get("result_json")).fields().forEachRemaining(e->r.put(e.getKey(),json.convertValue(e.getValue(),Object.class)));r.put("replayed",true);return r;}
    @Transactional(isolation=Isolation.READ_COMMITTED) public Map<String,Object> publish(long expected,String request){return change(expected,request,null);}
    @Transactional(isolation=Isolation.READ_COMMITTED) public Map<String,Object> rollback(long expected,String request,String releaseId){return change(expected,request,releaseId);}
    /** Transactional per-IP publication. State-only actions always use live content. */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> publishBrandIp(long expected,String request,String characterId,String action,String releaseId){
        var actor=access.require("content:publish");
        if(!Set.of("publish","list","delist","rollback").contains(action))throw ContentRules.fail("INVALID_IP_ACTION");
        var workspace=lock();
        String hash=RegistrationService.digest(encode(Arrays.asList("brand-ip",expected,characterId,action,releaseId)));
        var replayed=replay(actor.id(),request,hash);if(replayed!=null)return replayed;
        if(((Number)workspace.get("version")).longValue()!=expected)throw failure(HttpStatus.CONFLICT,"CONTENT_VERSION_CONFLICT");
        var live=published();var existing=BrandIpContent.character(live,characterId);
        JsonNode source;
        Boolean enabled=null;
        switch(action){
            case "list" -> {source=existing==null?BrandIpContent.publishableDraft(parse((String)workspace.get("draft")),characterId):live;enabled=true;}
            case "delist" -> {if(existing==null)throw ContentRules.fail("PUBLISH_CHARACTER_FIRST");source=live;enabled=false;}
            case "rollback" -> {if(existing==null||releaseId==null)throw ContentRules.fail("RELEASE_ID_REQUIRED");source=release(releaseId);}
            default -> source=BrandIpContent.publishableDraft(parse((String)workspace.get("draft")),characterId);
        }
        var document=BrandIpContent.merge(live,source,characterId,enabled);
        var refs=validateFiles(document);
        String id=keys.randomKey(16);
        db.update("INSERT INTO content_release(id,workspace_version,payload,actor_id,created_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP)",id,expected,encode(document),actor.id());
        for(String asset:refs)db.update("INSERT INTO content_release_asset(release_id,asset_id) VALUES(?,?)",id,asset);
        db.update("UPDATE content_workspace SET published_id=?,version=version+1 WHERE id=1",id);
        var result=Map.<String,Object>of("releaseId",id,"version",Long.toString(expected+1),"characterId",characterId,"replayed",false);
        db.update("INSERT INTO content_command(actor_id,request_id,payload_hash,result_json) VALUES(?,?,?,?)",actor.id(),requestKey(request),hash,encode(result));
        audit(actor,"BRAND_IP_"+action.toUpperCase(Locale.ROOT),characterId+":"+id);
        return result;
    }
    private Map<String,Object> change(long expected,String request,String target){
        var a=access.require("content:publish");var workspace=lock();String hash=RegistrationService.digest(encode(Arrays.asList(expected,target)));var old=replay(a.id(),request,hash);if(old!=null)return old;
        if(((Number)workspace.get("version")).longValue()!=expected)throw failure(HttpStatus.CONFLICT,"CONTENT_VERSION_CONFLICT");
        JsonNode candidate=target==null?parse((String)workspace.get("draft")):release(target);
        JsonNode doc=BrandIpContent.pinManaged(candidate,published(),db.queryForList("SELECT character_id FROM brand_ip_revision",String.class));
        Set<String> refs=validateFiles(doc);
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
    JsonNode published(){return publishedDocument(publishedId());}
    private JsonNode publishedDocument(String id){return id==null?parse("{\"schemaVersion\":1,\"characters\":[],\"appearances\":[],\"accessories\":[],\"pages\":[],\"products\":[]}"):release(id);}
    private List<Map<String,Object>> publishedAssets(String releaseId){if(releaseId==null)return List.of();var rows=db.queryForList("SELECT a.id,a.mime,a.width,a.height,a.size_bytes,a.sha256 FROM content_asset a JOIN content_release_asset r ON r.asset_id=a.id WHERE r.release_id=? ORDER BY a.id",releaseId);var result=new ArrayList<Map<String,Object>>();for(var row:rows)result.add(Map.of("id",row.get("id"),"mime",row.get("mime"),"width",row.get("width"),"height",row.get("height"),"size_bytes",row.get("size_bytes"),"sha256",row.get("sha256"),"url","/bird/v1/content/assets/"+row.get("id")));return result;}
    private Map<String,Object> capabilities(){return Map.of("schemaVersion",1,"imageFormats",List.of("image/png","image/jpeg","image/webp"),"animationFormats",List.of("sprite-grid","sprite-timeline"),"supportsAudio",true,"coordinateScale",1000);}
    public Map<String,Object> publicConfig(){String id=publishedId();return Map.of("releaseId",id==null?"":id,"content",publishedDocument(id),"assets",publishedAssets(id),"capabilities",capabilities());}
    public Map<String,Object> publicCharacter(String id){String releaseId=publishedId();var doc=publishedDocument(releaseId);JsonNode character=ContentRules.index(doc,"characters",500).get(id);if(character==null)throw failure(HttpStatus.NOT_FOUND,"CHARACTER_NOT_FOUND");return Map.of("character",character,"appearances",filter(doc,"appearances","characterId",id),"pages",doc.get("pages"),"releaseId",Objects.toString(releaseId,""),"assets",publishedAssets(releaseId),"capabilities",capabilities());}
    private List<JsonNode> filter(JsonNode doc,String group,String key,String id){List<JsonNode> list=new ArrayList<>();for(var n:doc.path(group))if(n.path(key).asText().equals(id))list.add(n);return list;}
    public List<Map<String,Object>> releases(long before){access.require("content:read");return db.queryForList("SELECT id,workspace_version,actor_id,created_at FROM content_release WHERE workspace_version<? ORDER BY workspace_version DESC LIMIT 50",before);}
    public List<Map<String,Object>> auditLog(long before){access.require("content:audit");return db.queryForList("SELECT oper_id,title,oper_name,oper_param,oper_time FROM sys_oper_log WHERE oper_id<? ORDER BY oper_id DESC LIMIT 50",before);}
    Map<String,Object> assetRow(String id){var rows=db.queryForList("SELECT * FROM content_asset WHERE id=?",id);if(rows.isEmpty())throw failure(HttpStatus.NOT_FOUND,"ASSET_NOT_FOUND");return rows.getFirst();}
    static List<Map<String,Object>> apiRows(List<Map<String,Object>> rows){
        var result=new ArrayList<Map<String,Object>>();for(var row:rows){var normalized=new LinkedHashMap<String,Object>();row.forEach((key,value)->normalized.put(key.toLowerCase(Locale.ROOT),value));result.add(normalized);}return result;
    }
    public List<Map<String,Object>> assets(long before,String kind){
        if(!Set.of("image","audio").contains(kind))throw ContentRules.fail("INVALID_MEDIA_KIND");access.require("content:read");return apiRows(db.queryForList("SELECT id,mime,size_bytes,width,height,original_name,license_note,created_at FROM content_asset WHERE mime LIKE ? ORDER BY created_at DESC,id LIMIT 100 OFFSET ?",kind+"/%",Math.min(Math.max(before,0),100000)));}
    @Transactional public Map<String,Object> upload(MultipartFile file,String license) throws IOException {
        var a=access.require("content:edit");if(file.getSize()==0||file.getSize()>10*1024*1024)throw ContentRules.fail("ASSET_SIZE_INVALID");
        String licenseNote=Objects.toString(license,"").strip();
        if(licenseNote.length()>500)throw ContentRules.fail("ASSET_LICENSE_TOO_LONG");
        byte[] data=file.getBytes();String mime;
        if(data.length>8&&data[0]==(byte)137&&data[1]==80&&data[2]==78&&data[3]==71)mime="image/png";
        else if(data.length>3&&data[0]==(byte)255&&data[1]==(byte)216&&data[2]==(byte)255)mime="image/jpeg";
        else if(data.length>=12&&signature(data,0,"RIFF")&&signature(data,8,"WEBP"))mime="image/webp";
        else if(data.length>=12&&signature(data,4,"ftyp")){
            requireAnimationEditing();
            if(licenseNote.isEmpty())throw ContentRules.fail("ASSET_LICENSE_REQUIRED");
            AnimationAudio.durationMs(data,json);
            return storeUpload(file,licenseNote,data,"audio/mp4",0,0,a);
        }else throw ContentRules.fail("ASSET_FORMAT_UNSUPPORTED");
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(data))){var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw ContentRules.fail("ASSET_CORRUPT");var reader=readers.next();try{reader.setInput(input);int w=reader.getWidth(0),h=reader.getHeight(0);if(w<1||h<1||w>8192||h>8192||(long)w*h>24000000)throw ContentRules.fail("ASSET_DIMENSIONS_INVALID");reader.read(0);
            return storeUpload(file,licenseNote,data,mime,w,h,a);
        }finally{reader.dispose();}}
    }

    private static boolean signature(byte[] data,int offset,String value){
        for(int i=0;i<value.length();i++)if(data[offset+i]!=value.charAt(i))return false;
        return true;
    }

    private Map<String,Object> storeUpload(MultipartFile file,String license,byte[] data,String mime,int width,int height,AdminAccess.Actor actor) throws IOException {
        String id=keys.randomKey(16);
        storage.put("originals/"+id,data,mime);
        storage.put(id,data,mime);
        if(width>0)storage.put("thumbnails/"+id+".png",createThumbnail(data),"image/png");
        String filename=Objects.toString(file.getOriginalFilename(),"media").replaceAll("[^\\p{L}\\p{N}._ -]","_");
        if(filename.length()>160)filename=filename.substring(0,160);
        db.update("INSERT INTO content_asset(id,sha256,mime,size_bytes,width,height,original_name,license_note,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",id,bytesDigest(data),mime,data.length,width,height,filename,license,actor.id());
        audit(actor,"UPLOAD_ASSET",id);
        return Map.of("assetId",id,"mime",mime,"width",width,"height",height,"size",data.length);
    }
    public byte[] readAsset(String id,boolean management,String preview){
        if(management)access.require("content:read");
        else if(preview!=null){if(!ContentRules.validate(preview(preview)).contains(id))throw failure(HttpStatus.NOT_FOUND,"ASSET_NOT_FOUND");}
        else if(db.queryForObject("SELECT COUNT(*) FROM content_release_asset WHERE asset_id=?",Integer.class,id)==0)throw failure(HttpStatus.NOT_FOUND,"ASSET_NOT_PUBLISHED");
        assetRow(id);try{return storage.read(id);}catch(IOException e){throw failure(HttpStatus.NOT_FOUND,"ASSET_UNAVAILABLE");}
    }
    public byte[] thumbnail(String id){
        access.require("content:read");
        if("audio/mp4".equals(assetRow(id).get("mime")))throw failure(HttpStatus.NOT_FOUND,"THUMBNAIL_NOT_AVAILABLE");
        String key="thumbnails/"+id+".png";
        try {
            var cached=storage.find(key);if(cached.isPresent())return cached.get();
            byte[] result=createThumbnail(storage.read(id));
            storage.put(key,result,"image/png");return result;
        } catch(IOException e){throw failure(HttpStatus.SERVICE_UNAVAILABLE,"ASSET_UNAVAILABLE");}
    }
    static byte[] createThumbnail(byte[] data) throws IOException {
        var source=ImageIO.read(new ByteArrayInputStream(data));if(source==null)throw new IOException("Invalid source image");
        double scale=Math.min(1,160.0/Math.max(source.getWidth(),source.getHeight()));
        var image=new java.awt.image.BufferedImage(Math.max(1,(int)(source.getWidth()*scale)),Math.max(1,(int)(source.getHeight()*scale)),java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var graphics=image.createGraphics();try{graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);graphics.drawImage(source,0,0,image.getWidth(),image.getHeight(),null);}finally{graphics.dispose();}
        var output=new ByteArrayOutputStream();if(!ImageIO.write(image,"png",output))throw new IOException("PNG encoder unavailable");return output.toByteArray();
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
    Map<String,Object> previewDocument(JsonNode document){
        var actor=access.require("content:preview");validateFiles(document);
        String token=keys.randomKey(32);Instant expiry=Instant.now().plusSeconds(900);
        db.update("INSERT INTO content_preview(token_hash,actor_id,payload,expires_at) VALUES(?,?,?,?)",RegistrationService.digest(token),actor.id(),encode(document),Timestamp.from(expiry));
        audit(actor,"BRAND_IP_PREVIEW","15min");return Map.of("token",token,"expiresAt",expiry,"content",document);
    }
    public JsonNode preview(String token){if(token==null||!token.matches("[0-9a-f]{64}"))throw failure(HttpStatus.UNAUTHORIZED,"PREVIEW_EXPIRED");var list=db.queryForList("SELECT p.payload FROM content_preview p JOIN sys_user u ON u.user_id=p.actor_id WHERE p.token_hash=? AND p.expires_at>CURRENT_TIMESTAMP AND u.status='0'",String.class,RegistrationService.digest(token));if(list.isEmpty())throw failure(HttpStatus.UNAUTHORIZED,"PREVIEW_EXPIRED");return parse(list.getFirst());}
    public List<Map<String,Object>> entities(String after){access.require("content:read");return db.queryForList("SELECT b.id,b.name,b.ip_code,b.active,c.character_id FROM bird b LEFT JOIN content_entity c ON c.bird_id=b.id WHERE b.id>? ORDER BY b.id LIMIT 50",after);}
    @Transactional public void mapEntity(String bird,String character){var a=access.require("content:edit");lock();if(!ContentRules.index(published(),"characters",500).containsKey(character))throw ContentRules.fail("PUBLISH_CHARACTER_FIRST");var birds=db.queryForList("SELECT id,ip_code FROM bird WHERE id=? FOR UPDATE",bird);if(birds.isEmpty())throw failure(HttpStatus.NOT_FOUND,"BIRD_NOT_FOUND");var previous=db.queryForList("SELECT character_id FROM content_entity WHERE bird_id=?",String.class,bird);
        if(!previous.isEmpty()&&!previous.getFirst().equals(character))throw failure(HttpStatus.CONFLICT,"ENTITY_CHARACTER_IMMUTABLE");
        if(birds.getFirst().get("ip_code")!=null&&!birds.getFirst().get("ip_code").equals(ContentRules.index(published(),"characters",500).get(character).path("code").asText()))throw ContentRules.fail("EXISTING_IP_CODE_MISMATCH");
        if(previous.isEmpty())db.update("INSERT INTO content_entity(bird_id,character_id) VALUES(?,?)",bird,character);audit(a,"MAP_ENTITY",bird+":"+character);}
    public String characterForBird(String bird){var list=db.queryForList("SELECT character_id FROM content_entity WHERE bird_id=?",String.class,bird);return list.isEmpty()?null:list.getFirst();}
}
