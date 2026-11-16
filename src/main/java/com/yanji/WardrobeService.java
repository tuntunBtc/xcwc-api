package com.yanji;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class WardrobeService {
    private final JdbcTemplate db;private final RegistrationService users;private final ContentService content;
    public WardrobeService(JdbcTemplate db,RegistrationService users,ContentService content){this.db=db;this.users=users;this.content=content;}
    public record Acquire(String requestId,String itemId,String expectedReleaseId){}
    public record Wear(String requestId,String expectedVersion,String appearanceId,Map<String,String> accessories){}
    private RegistrationService.Failure failure(HttpStatus s,String c){return ContentService.failure(s,c);}
    private String lock(String auth){String u=users.userId(auth);db.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE",String.class,u);users.userId(auth);return u;}
    private byte[] key(String k){if(k==null||!k.matches("[A-Za-z0-9_-]{16,64}"))throw ContentRules.fail("INVALID_REQUEST_ID");return k.getBytes(StandardCharsets.US_ASCII);}
    private Map<String,Object> replay(String user,String id,String hash){var r=db.queryForList("SELECT payload_hash,result_json FROM wardrobe_operation WHERE user_id=? AND request_id=?",user,key(id));if(r.isEmpty())return null;if(!r.getFirst().get("payload_hash").equals(hash))throw failure(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT");return result((String)r.getFirst().get("result_json"),true);}
    private Map<String,Object> result(String text,boolean replayed){var map=new LinkedHashMap<String,Object>();content.parse(text).fields().forEachRemaining(e->map.put(e.getKey(),e.getValue()));map.put("replayed",replayed);return map;}
    private Map<String,Object> record(String user,String id,String hash,Map<String,Object> result){result.put("requestId",id);result.put("replayed",false);db.update("INSERT INTO wardrobe_operation(user_id,request_id,payload_hash,result_json) VALUES(?,?,?,?)",user,key(id),hash,content.encode(result));return result;}
    private Map<String,JsonNode> items(JsonNode doc){var all=ContentRules.index(doc,"appearances",5000);all.putAll(ContentRules.index(doc,"accessories",5000));return all;}
    private Map<String,JsonNode> ownedItems(String user){var result=new LinkedHashMap<String,JsonNode>();for(var r:db.queryForList("SELECT item_id,item_json FROM wardrobe_entitlement WHERE user_id=?",user))result.put((String)r.get("item_id"),content.parse((String)r.get("item_json")));return result;}
    public Map<String,Object> catalog(String auth){String user=users.userId(auth);var current=content.published();var all=items(current);var owned=ownedItems(user);owned.forEach(all::putIfAbsent);return Map.of("releaseId",Objects.toString(content.publishedId(),""),"items",all.values(),"ownedIds",owned.keySet(),"characters",current.get("characters"));}
    @Transactional(isolation=Isolation.READ_COMMITTED) public Map<String,Object> acquire(String auth,Acquire command){
        String user=lock(auth);String hash=RegistrationService.digest(content.encode(command));var replay=replay(user,command.requestId(),hash);if(replay!=null)return replay;
        db.queryForObject("SELECT id FROM content_workspace WHERE id=1 FOR UPDATE",Integer.class);
        var current=content.published();String release=content.publishedId();String id=command.itemId();
        if(id==null||!id.matches("[A-Za-z0-9_-]{1,32}"))throw ContentRules.fail("INVALID_ITEM_ID");
        boolean owned=db.queryForObject("SELECT COUNT(*) FROM wardrobe_entitlement WHERE user_id=? AND item_id=?",Integer.class,user,id)>0;
        long price=0;
        if(!owned){
            if(release==null||!release.equals(command.expectedReleaseId()))throw failure(HttpStatus.CONFLICT,"CONTENT_RELEASE_CHANGED");
            JsonNode item=items(current).get(id);if(item==null||!item.path("available").asBoolean())throw failure(HttpStatus.NOT_FOUND,"ITEM_NOT_AVAILABLE");
            price=item.path("price").longValue();long balance=db.queryForObject("SELECT points_balance FROM app_user WHERE id=?",Long.class,user);
            if(balance<price)throw failure(HttpStatus.CONFLICT,"INSUFFICIENT_POINTS");
            db.update("INSERT INTO wardrobe_entitlement(user_id,item_id,item_type,item_json,release_id,acquired_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP)",user,id,item.has("characterId")?"APPEARANCE":"ACCESSORY",content.encode(item),release);
            db.update("UPDATE app_user SET points_balance=points_balance-?,state_version=state_version+1 WHERE id=?",price,user);
            db.update("INSERT INTO points_ledger(user_id,request_id,operation,target_id,delta,balance_after,created_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)",user,"wardrobe_"+users.randomKey(16),"WARDROBE",id,-price,balance-price);
        }
        var response=new LinkedHashMap<String,Object>();response.put("itemId",id);response.put("charged",price);response.put("alreadyOwned",owned);response.put("balance",db.queryForObject("SELECT points_balance FROM app_user WHERE id=?",Long.class,user));return record(user,command.requestId(),hash,response);
    }
    private String character(String user,String pet){var rows=db.queryForList("SELECT bird_id FROM bird_binding WHERE id=? AND user_id=?",String.class,pet,user);if(rows.isEmpty())throw failure(HttpStatus.NOT_FOUND,"PET_NOT_FOUND");String id=content.characterForBird(rows.getFirst());if(id==null)throw failure(HttpStatus.CONFLICT,"PET_CHARACTER_NOT_CONFIGURED");return id;}
    private JsonNode characterConfig(String id){var doc=content.published();var role=ContentRules.index(doc,"characters",500).get(id);if(role!=null)return role;for(String payload:db.queryForList("SELECT payload FROM content_release ORDER BY workspace_version DESC",String.class)){role=ContentRules.index(content.parse(payload),"characters",500).get(id);if(role!=null)return role;}throw failure(HttpStatus.CONFLICT,"PET_CHARACTER_NOT_CONFIGURED");}
    private Map<String,JsonNode> availableItems(String user,String role){var result=items(content.published());ownedItems(user).forEach(result::putIfAbsent);String defaultId=characterConfig(role).path("defaultAppearanceId").asText();if(!result.containsKey(defaultId)){for(String p:db.queryForList("SELECT payload FROM content_release ORDER BY workspace_version DESC",String.class)){var a=items(content.parse(p)).get(defaultId);if(a!=null){result.put(defaultId,a);break;}}}return result;}
    private Map<String,Object> outfit(String user,String pet,String role){var rows=db.queryForList("SELECT * FROM wardrobe_outfit WHERE binding_id=?",pet);if(rows.isEmpty())return Map.of("petId",pet,"characterId",role,"version","0","appearanceId",characterConfig(role).get("defaultAppearanceId").asText(),"accessories",Map.of());var r=rows.getFirst();return Map.of("petId",pet,"characterId",role,"version",r.get("version").toString(),"appearanceId",r.get("appearance_id"),"accessories",content.parse((String)r.get("accessories_json")));}
    public Map<String,Object> outfit(String auth,String pet){String user=users.userId(auth);String role=character(user,pet);return outfit(user,pet,role);}
    private boolean compatible(JsonNode accessory,String role,String look){for(var c:accessory.path("compatible"))if(c.path("characterId").asText().equals(role)&&c.path("appearanceId").asText().equals(look))return true;return false;}
    @Transactional(isolation=Isolation.READ_COMMITTED) public Map<String,Object> wear(String auth,String pet,Wear command){
        String user=lock(auth);String hash=RegistrationService.digest(content.encode(List.of(pet,command)));var prior=replay(user,command.requestId(),hash);if(prior!=null)return prior;
        db.queryForObject("SELECT id FROM content_workspace WHERE id=1 FOR UPDATE",Integer.class);
        String role=character(user,pet);var before=outfit(user,pet,role);if(!Objects.equals(before.get("version"),command.expectedVersion()))throw failure(HttpStatus.CONFLICT,"OUTFIT_VERSION_CONFLICT");
        var owned=ownedItems(user);var all=availableItems(user,role);var look=all.get(command.appearanceId());String defaultId=characterConfig(role).path("defaultAppearanceId").asText();
        if(look==null||!look.path("characterId").asText().equals(role)||(!command.appearanceId().equals(defaultId)&&!owned.containsKey(command.appearanceId())))throw failure(HttpStatus.FORBIDDEN,"APPEARANCE_NOT_OWNED_OR_COMPATIBLE");
        boolean preserve=command.accessories()==null;Map<String,String> requested=new TreeMap<>();
        if(preserve){content.parse(content.encode(before.get("accessories"))).fields().forEachRemaining(e->requested.put(e.getKey(),e.getValue().asText()));}else requested.putAll(command.accessories());
        if(requested.size()>12)throw ContentRules.fail("TOO_MANY_ACCESSORIES");
        List<String> removed=new ArrayList<>();Map<String,String> selected=new TreeMap<>();
        for(var e:requested.entrySet()){
            var item=all.get(e.getValue());if(item==null||!owned.containsKey(e.getValue())||!item.path("slot").asText().equals(e.getKey()))throw failure(HttpStatus.FORBIDDEN,"ACCESSORY_NOT_OWNED");
            if(!compatible(item,role,command.appearanceId())){if(!preserve)throw failure(HttpStatus.CONFLICT,"ACCESSORY_INCOMPATIBLE");removed.add(e.getValue());}else selected.put(e.getKey(),e.getValue());
        }
        long version=Long.parseLong(command.expectedVersion())+1;
        if(db.update("UPDATE wardrobe_outfit SET version=?,appearance_id=?,accessories_json=? WHERE binding_id=?",version,command.appearanceId(),content.encode(selected),pet)==0)db.update("INSERT INTO wardrobe_outfit(binding_id,version,appearance_id,accessories_json) VALUES(?,?,?,?)",pet,version,command.appearanceId(),content.encode(selected));
        var response=new LinkedHashMap<String,Object>(outfit(user,pet,role));response.put("removedAccessoryIds",removed);return record(user,command.requestId(),hash,response);
    }
    public Map<String,Object> operation(String auth,String request){var rows=db.queryForList("SELECT result_json FROM wardrobe_operation WHERE user_id=? AND request_id=?",String.class,users.userId(auth),key(request));if(rows.isEmpty())throw failure(HttpStatus.NOT_FOUND,"OPERATION_NOT_FOUND");return result(rows.getFirst(),true);}
}
