package com.yanji;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BrandIpService {
    private final JdbcTemplate db;
    private final ContentService content;
    private final AdminAccess access;
    private final RegistrationService keys;
    static final List<String> NAMES=List.of("正常","开心","饥饿","口渴","需要陪伴","害羞","生气","大哭","疑惑","很开心");
    public BrandIpService(JdbcTemplate db,ContentService content,AdminAccess access,RegistrationService keys){this.db=db;this.content=content;this.access=access;this.keys=keys;}
    private ObjectNode draft(){return (ObjectNode)content.parse(db.queryForObject("SELECT draft FROM content_workspace WHERE id=1",String.class));}
    private Map<String,Object> lock(){return db.queryForMap("SELECT * FROM content_workspace WHERE id=1 FOR UPDATE");}
    private JsonNode role(JsonNode doc,String id){var r=BrandIpContent.character(doc,id);if(r==null)throw ContentService.failure(HttpStatus.NOT_FOUND,"CHARACTER_NOT_FOUND");return r;}
    private JsonNode look(JsonNode doc,JsonNode role){return ContentRules.index(doc,"appearances",5000).get(role.path("defaultAppearanceId").asText());}
    private String expressionId(String look,String type){return UUID.nameUUIDFromBytes(("expression:"+look+":"+type).getBytes(StandardCharsets.UTF_8)).toString().replace("-","");}
    private ObjectNode normalized(JsonNode doc,String id){
        var r=role(doc,id);var l=look(doc,r);ObjectNode item=r.deepCopy();item.remove("adoptionEnabled");
        ObjectNode result=content.parse("{}").deepCopy();result.set("role",item);result.set("look",l);return result;
    }
    private ObjectNode normalizedForComparison(JsonNode doc,String id){
        var normalized=normalized(doc,id).deepCopy();
        if(normalized.path("look").isObject()){
            var look=(ObjectNode)normalized.path("look");
            String normal=BrandIpContent.activeExpression(look,"normal");
            BrandIpContent.fillOptionalExpressions(look);
            var brand=normalized.path("role").path("brand");
            if(!normal.isBlank()&&brand.isObject())((ObjectNode)brand).put("coverAssetId",normal);
        }
        return normalized;
    }
    private Map<String,Object> revision(String id){var rows=db.queryForList("SELECT * FROM brand_ip_revision WHERE character_id=?",id);return rows.isEmpty()?Map.of("draft_revision",0,"state_revision",0,"updated_at",""):rows.getFirst();}
    private String draftToken(JsonNode doc,String id){return revision(id).get("draft_revision")+":"+RegistrationService.digest(content.encode(normalized(doc,id)));}
    private String stateToken(JsonNode live,String id){var r=BrandIpContent.character(live,id);return revision(id).get("state_revision")+":"+(r==null?"new":r.path("adoptionEnabled").asBoolean()?"listed":"delisted");}
    private void check(String expected,String actual){if(!Objects.equals(expected,actual))throw ContentService.failure(HttpStatus.CONFLICT,"CONTENT_VERSION_CONFLICT");}
    private void touch(String id,boolean state){
        if(db.queryForObject("SELECT COUNT(*) FROM brand_ip_revision WHERE character_id=?",Integer.class,id)==0)db.update("INSERT INTO brand_ip_revision(character_id) VALUES(?)",id);
        db.update("UPDATE brand_ip_revision SET "+(state?"state_revision=state_revision+1":"draft_revision=draft_revision+1")+",updated_at=CURRENT_TIMESTAMP WHERE character_id=?",id);
    }
    public Map<String,Object> detail(String id){access.require("brand:read");return describe(draft(),content.published(),id);}
    private Map<String,Object> describe(JsonNode doc,JsonNode live,String id){
        var r=role(doc,id);var l=look(doc,r);var released=BrandIpContent.character(live,id);
        var out=new LinkedHashMap<String,Object>();out.put("id",id);out.put("code",r.path("code").asText());out.put("name",r.path("name").asText());out.put("description",r.path("description").asText());
        out.put("city",r.path("brand").path("cityName").asText());out.put("cityCode",r.path("brand").path("cityCode").asText());out.put("coverAssetId",r.path("brand").path("coverAssetId").asText());
        out.put("backgroundAssetId",r.path("backgroundAssetId").asText(null));
        out.put("status",released==null?"new":released.path("adoptionEnabled").asBoolean()?"listed":"delisted");
        out.put("dirty",released==null||!normalizedForComparison(doc,id).equals(normalizedForComparison(live,id)));
        out.put("draftVersion",draftToken(doc,id));out.put("stateVersion",stateToken(live,id));out.put("updatedAt",revision(id).get("updated_at").toString());
        var expressions=new ArrayList<Map<String,Object>>();int count=0;
        for(int i=0;i<BrandIpContent.STATES.size();i++){
            String type=BrandIpContent.STATES.get(i);String asset=l==null?"":l.path("slots").path(type).asText();
            var meta=l==null?null:l.path("expressions").path(type);
            if(meta!=null&&meta.has("assetId"))asset=meta.path("assetId").asText();
            if(!asset.isBlank())count++;
            boolean listed=!asset.isBlank()&&(meta==null||meta.path("listed").asBoolean(true));
            expressions.add(Map.of("listed",listed,"type",type,"id",meta!=null&&meta.hasNonNull("id")?meta.get("id").asText():expressionId(r.path("defaultAppearanceId").asText(),type),"name",meta!=null&&meta.hasNonNull("name")?meta.get("name").asText():NAMES.get(i),"assetId",asset));
        }
        out.put("animationEditingEnabled",content.animationEditing());
        out.put("mainAssetId",expressions.stream().map(e->(String)e.get("assetId")).filter(asset->!asset.isBlank()).findFirst().orElse(""));out.put("expressions",expressions);out.put("configured",count);out.put("animation",l==null?null:l.get("animation"));
        out.put("mappedEntities",db.queryForObject("SELECT COUNT(*) FROM content_entity WHERE character_id=?",Integer.class,id));
        out.put("unmappedEntities",db.queryForObject("SELECT COUNT(*) FROM bird b LEFT JOIN content_entity e ON e.bird_id=b.id WHERE e.bird_id IS NULL AND b.ip_code=?",Integer.class,r.path("code").asText()));
        return out;
    }
    public Map<String,Object> list(String query,String city,String status,int page,int size){
        access.require("brand:read");var d=draft();var live=content.published();var items=new ArrayList<Map<String,Object>>();var cities=new TreeSet<String>();
        cities.addAll(List.of("杭州","北京","上海","广州","深圳","南京","苏州","成都","重庆","西安","武汉","长沙","宁波","温州","嘉兴","湖州","绍兴","金华","衢州","舟山","台州","丽水"));
        String needle=Objects.toString(query,"").trim().toLowerCase(Locale.ROOT);
        var matching=new ArrayList<String>();
        for(var r:d.path("characters")){
            String id=r.path("id").asText(),c=r.path("brand").path("cityName").asText();if(!c.isBlank())cities.add(c);
            var published=BrandIpContent.character(live,id);String currentStatus=published==null?"new":published.path("adoptionEnabled").asBoolean()?"listed":"delisted";
            if((needle.isEmpty()||(r.path("name").asText()+" "+id).toLowerCase(Locale.ROOT).contains(needle))&&(city==null||city.isEmpty()||city.equals(c))&&(status==null||status.isEmpty()||status.equals(currentStatus)))matching.add(id);
        }
        size=Math.max(1,Math.min(100,size));int from=(int)Math.min(matching.size(),(long)Math.max(0,page-1)*size);
        for(String id:matching.subList(from,Math.min(matching.size(),from+size)))items.add(describe(d,live,id));
        return Map.of("items",items,"total",matching.size(),"cities",cities);
    }
    @Transactional public Map<String,Object> create(JsonNode input){
        var actor=access.require("brand:edit");lock();var d=draft();if(d.path("characters").size()>=500)throw ContentRules.fail("CONTENT_TOO_LARGE");
        String id=keys.randomKey(16),lookId=keys.randomKey(16);var r=d.withArray("characters").addObject();r.put("id",id).put("code",id).put("name","未命名 IP").put("description","").put("defaultAppearanceId",lookId).put("adoptionEnabled",false);
        var l=d.withArray("appearances").addObject();l.put("id",lookId).put("characterId",id).put("name","默认造型").put("price",0).put("available",true);l.putObject("slots");
        apply(d,id,input);write(d);touch(id,false);content.audit(actor,"BRAND_IP_CREATE",id);return describe(d,content.published(),id);
    }
    @Transactional public Map<String,Object> save(String id,String expected,JsonNode input){
        var actor=access.require("brand:edit");lock();var d=draft();check(expected,draftToken(d,id));apply(d,id,input);write(d);touch(id,false);content.audit(actor,"BRAND_IP_SAVE",id);return describe(d,content.published(),id);
    }
    private void write(JsonNode d){String value=content.encode(d);if(value.length()>2000000)throw ContentRules.fail("CONTENT_TOO_LARGE");db.update("UPDATE content_workspace SET draft=?,version=version+1 WHERE id=1",value);}
    private void apply(ObjectNode d,String id,JsonNode input){
        ContentRules.fields(input,"name","description","city","coverAssetId","backgroundAssetId","expressions","code","animation");var r=(ObjectNode)role(d,id);var l=(ObjectNode)look(d,r);
        if(input.has("backgroundAssetId")){
            var background=input.get("backgroundAssetId");
            if(background.isNull()){
                r.remove("backgroundAssetId");
            }else{
                ContentRules.asset(background,new HashSet<>());
                var asset=content.assetRow(background.asText());
                if(!Set.of("image/png","image/jpeg","image/webp").contains(Objects.toString(asset.get("mime"),"")))throw ContentRules.fail("ASSET_TYPE_MISMATCH");
                r.put("backgroundAssetId",background.asText());
            }
        }
        if(input.has("animation")&&!Objects.equals(input.get("animation"),l.path("animation"))){
            if(input.hasNonNull("animation")||l.hasNonNull("animation")){
                content.requireAnimationEditing();
                if(input.hasNonNull("animation")){
                    var anim=input.get("animation");
                    ContentRules.animation(anim,new HashSet<>(),new HashSet<>());
                    l.set("animation",anim.deepCopy());
                }else l.remove("animation");
            }
        }
        String name=ContentRules.string(input,"name",60);r.put("name",name);
        if(input.has("code")&&!input.path("code").asText().isBlank()){
            String code=ContentRules.id(input,"code");var published=BrandIpContent.character(content.published(),id);
            if(published!=null&&!published.path("code").asText().equals(code))throw ContentRules.fail("PUBLISHED_IDENTITY_CANNOT_CHANGE");
            for(var other:d.path("characters"))if(!other.path("id").asText().equals(id)&&other.path("code").asText().equals(code))throw ContentRules.fail("DUPLICATE_CHARACTER_CODE");r.put("code",code);
        }
        String description=input.path("description").asText(r.path("description").asText(""));if(description.length()>500)throw ContentRules.fail("INVALID_DESCRIPTION");r.put("description",description);
        String city=input.path("city").asText(r.path("brand").path("cityName").asText("")).trim().replaceAll("市$","");if(city.length()>60||city.codePoints().anyMatch(Character::isISOControl))throw ContentRules.fail("INVALID_CITY");
        String previousCover=r.path("brand").path("coverAssetId").asText();var b=r.putObject("brand");b.put("cityName",city).put("cityCode",city.isEmpty()?"":"city_"+RegistrationService.digest(city).substring(0,15));
        String cover=previousCover;
        String requestedCover=input.path("coverAssetId").asText("");
        if(!requestedCover.isEmpty()){ContentRules.asset(input.get("coverAssetId"),new HashSet<>());content.assetRow(requestedCover);}
        b.put("coverAssetId",requestedCover.isEmpty()?cover:requestedCover);
        if(input.has("expressions")){
            var entries=ContentRules.array(input,"expressions",10);
            if(entries.size()!=10)throw ContentRules.fail("EXPRESSION_STATES_REQUIRED");
            var old=l.path("expressions");
            var metadata=l.objectNode();
            var slots=l.objectNode();
            Set<String> seen=new HashSet<>();
            Map<String,String> active=new LinkedHashMap<>();
            Map<String,JsonNode> byType=new HashMap<>();
            for(var e:entries){
                ContentRules.fields(e,"id","type","name","assetId","listed");
                String type=e.path("type").asText();
                if(!BrandIpContent.STATES.contains(type)||!seen.add(type))throw ContentRules.fail("INVALID_EXPRESSION_STATE");
                byType.put(type,e);
            }
            for(String type:BrandIpContent.STATES){
                var e=byType.get(type);
                String expected=old.path(type).path("id").asText(expressionId(l.path("id").asText(),type));
                if(!expected.equals(e.path("id").asText()))throw ContentRules.fail("EXPRESSION_ID_IMMUTABLE");
                String asset=e.path("assetId").asText("");
                if(!asset.isEmpty()){ContentRules.asset(e.get("assetId"),new HashSet<>());content.assetRow(asset);}
                if(e.has("listed"))ContentRules.bool(e,"listed");
                boolean listed=!asset.isEmpty()&&e.path("listed").asBoolean(true);
                metadata.putObject(type).put("id",expected).put("name",ContentRules.string(e,"name",60)).put("assetId",asset).put("listed",listed);
                if(listed)active.put(type,asset);
            }
            String fallback=active.getOrDefault("normal",active.values().stream().findFirst().orElse(""));
            for(String type:BrandIpContent.STATES)slots.put(type,active.getOrDefault(type,fallback));
            l.set("expressions",metadata);
            l.set("slots",slots);
            b.put("coverAssetId",fallback);
        }
    }
    private void validateCity(JsonNode d,String id){
        if(role(d,id).path("brand").path("cityName").asText().isBlank())throw ContentRules.fail("IP_CITY_REQUIRED");
    }
    @Transactional public Map<String,Object> action(String id,String action,String draftVersion,String stateVersion,String requestId,String releaseId){
        if(action==null||!Set.of("publish","list","delist","rollback").contains(action))throw ContentRules.fail("INVALID_IP_ACTION");
        var actor=access.require(switch(action){case "list","delist"->"brand:status";case "rollback"->"brand:rollback";default->"brand:publish";});
        var work=lock();var d=draft();var live=content.published();role(d,id);
        String hash=RegistrationService.digest(content.encode(Arrays.asList("brand-command",id,action,draftVersion,stateVersion,releaseId)));
        var replay=content.replay(actor.id(),requestId,hash);if(replay!=null)return replay;
        check(stateVersion,stateToken(live,id));
        boolean first=BrandIpContent.character(live,id)==null;
        if(action.equals("publish")||(action.equals("list")&&first)){check(draftVersion,draftToken(d,id));validateCity(d,id);}
        if(action.equals("rollback")){check(draftVersion,draftToken(d,id));if(db.queryForObject("SELECT COUNT(*) FROM brand_ip_history WHERE character_id=? AND release_id=?",Integer.class,id,releaseId)==0)throw ContentRules.fail("IP_HISTORY_NOT_FOUND");}
        // Inner release command has its own request ID; the external receipt captures per-IP tokens.
        var result=content.publishBrandIp(((Number)work.get("version")).longValue(),keys.randomKey(16),id,action,releaseId);
        touch(id,true);
        if(action.equals("publish")||(action.equals("list")&&first))touch(id,false);
        db.update("INSERT INTO brand_ip_history(character_id,release_id,action,actor_id) VALUES(?,?,?,?)",id,result.get("releaseId"),action,actor.id());
        db.update("INSERT INTO content_command(actor_id,request_id,payload_hash,result_json) VALUES(?,?,?,?)",actor.id(),content.requestKey(requestId),hash,content.encode(result));
        return result;
    }
    public List<Map<String,Object>> history(String id){access.require("brand:audit");role(draft(),id);return ContentService.apiRows(db.queryForList("SELECT h.id,h.release_id,h.action,h.created_at,u.nick_name FROM brand_ip_history h LEFT JOIN sys_user u ON u.user_id=h.actor_id WHERE character_id=? ORDER BY h.id DESC LIMIT 50",id));}
    @Transactional public Map<String,Object> preview(String id){access.require("brand:preview");lock();var d=draft();validateCity(d,id);return content.previewDocument(BrandIpContent.merge(content.published(),BrandIpContent.publishableDraft(d,id),id,null));}
    @Transactional public void delete(String id,String expected){
        var actor=access.require("brand:edit");lock();var d=draft();check(expected,draftToken(d,id));
        if(BrandIpContent.character(content.published(),id)!=null||db.queryForObject("SELECT COUNT(*) FROM content_entity WHERE character_id=?",Integer.class,id)>0||db.queryForObject("SELECT COUNT(*) FROM brand_ip_history WHERE character_id=?",Integer.class,id)>0)throw ContentRules.fail("PUBLISHED_ITEM_CANNOT_BE_DELETED");
        String lookId=role(d,id).path("defaultAppearanceId").asText();
        for(var a:d.path("accessories"))for(var fit:a.path("compatible"))if(fit.path("characterId").asText().equals(id))throw ContentRules.fail("ASSET_STILL_REFERENCED");
        for(String group:List.of("characters","appearances")){var arr=d.withArray(group);for(int i=arr.size()-1;i>=0;i--)if((group.equals("characters")?arr.get(i).path("id").asText().equals(id):arr.get(i).path("characterId").asText().equals(id)))arr.remove(i);}
        write(d);content.audit(actor,"BRAND_IP_DELETE_DRAFT",id);
    }
}
