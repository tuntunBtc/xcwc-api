package com.yanji;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name="nest-interactions.enabled",havingValue="true")
public class NestInteractionService {
    private static final Set<String> COMPANION = Set.of("awake_idle","fall_asleep","sleep_idle","wake_up","head_pat");
    private final JdbcTemplate db;
    private final ContentService content;
    private final AdminAccess access;

    public NestInteractionService(JdbcTemplate db, ContentService content, AdminAccess access) {
        this.db=db; this.content=content; this.access=access;
    }
    public record View(String characterId,long version,long publishedVersion,JsonNode actions) {}
    private record Snapshot(long version,long published,JsonNode draft,JsonNode live) {}
    private record Asset(String url,String mime,int width,int height,String sha) {}
    private Snapshot snapshot(String id, boolean lock) {
        var rows=db.query("SELECT draft_version,published_version,draft_json,published_json FROM nest_interaction_workspace WHERE character_id=?"+(lock?" FOR UPDATE":""),
            (rs,n)->new Snapshot(rs.getLong(1),rs.getLong(2),content.parse(rs.getString(3)),rs.getString(4)==null?null:content.parse(rs.getString(4))),id);
        return rows.isEmpty()?new Snapshot(0,0,content.parse("[]"),null):rows.getFirst();
    }
    private void requireRole(String id, boolean lock) {
        var doc=content.parse(db.queryForObject("SELECT draft FROM content_workspace WHERE id=1"+(lock?" FOR UPDATE":""),String.class));
        if(BrandIpContent.character(doc,id)==null)throw ContentService.failure(HttpStatus.NOT_FOUND,"CHARACTER_NOT_FOUND");
    }
    private void checkVersion(Long expected, long current) {
        if(expected==null)throw invalid("NEST_VERSION_REQUIRED");
        if(expected!=current)throw ContentService.failure(HttpStatus.CONFLICT,"NEST_VERSION_CONFLICT");
    }
    private static RegistrationService.Failure invalid(String code) {return ContentService.failure(HttpStatus.BAD_REQUEST,code);}
    public View draft(String id) {
        access.require("brand:read");
        requireRole(id,false);
        return draftView(id,snapshot(id,false));
    }
    private View draftView(String id,Snapshot s){return new View(id,s.version(),s.published(),s.draft());}

    @Transactional public View save(String id,Long expected,JsonNode actions) {
        var actor=access.require("brand:edit");
        requireRole(id,true);
        var previous=snapshot(id,true);
        checkVersion(expected,previous.version());
        ArrayNode normalized=normalize(actions,false);
        if(normalized.equals(previous.draft()))return draftView(id,previous);
        long next=previous.version()+1;
        if(previous.version()==0)db.update("INSERT INTO nest_interaction_workspace(character_id,draft_version,draft_json) VALUES(?,?,?)",id,next,content.encode(normalized));
        else db.update("UPDATE nest_interaction_workspace SET draft_version=?,draft_json=? WHERE character_id=?",next,content.encode(normalized),id);
        content.audit(actor,"NEST_DRAFT_SAVE",id);
        return new View(id,next,previous.published(),normalized);
    }

    @Transactional public View publish(String id,Long expected) {
        var actor=access.require("brand:publish");
        requireRole(id,true);
        var previous=snapshot(id,true);
        checkVersion(expected,previous.version());
        if(previous.version()==0)throw invalid("NEST_DRAFT_EMPTY");
        if(previous.published()==previous.version())return draftView(id,previous);
        var live=normalize(previous.draft(),true);
        db.update("UPDATE nest_interaction_workspace SET published_version=?,published_json=? WHERE character_id=?",previous.version(),content.encode(live),id);
        content.audit(actor,"NEST_PUBLISH",id);
        return new View(id,previous.version(),previous.version(),previous.draft());
    }
    public View live(String id) {
        var s=snapshot(id,false);
        if(s.live()==null || BrandIpContent.character(content.published(),id)==null)throw ContentService.failure(HttpStatus.NOT_FOUND,"NEST_NOT_PUBLISHED");
        return new View(id,s.published(),s.published(),s.live());
    }

    private ArrayNode normalize(JsonNode source,boolean publishing) {
        if(source==null||!source.isArray()||source.size()>128)throw invalid("NEST_ACTIONS_INVALID");
        var result=(ArrayNode)content.parse("[]");
        Set<String> names=new HashSet<>(),codes=new HashSet<>();
        Map<String,Asset> assets=new HashMap<>();
        for(var row:source){
            String code=row.path("actionCode").asText(""),group=row.path("group").asText("");
            String expectedGroup=COMPANION.contains(code)?"daily":code.matches("(eat|drink|food-[0-9a-f]{32})")?"food":
                code.matches("(angry|emotion-[0-9a-f]{32})")?"emotion":null;
            if(!group.equals(expectedGroup)||!codes.add(code))throw invalid("NEST_ACTION_CODE_INVALID");
            String name=Normalizer.normalize(row.path("name").asText(""),Normalizer.Form.NFKC).strip();
            if(!row.path("name").isTextual()||name.isEmpty()||name.codePointCount(0,name.length())>20||name.codePoints().anyMatch(Character::isISOControl)||!names.add(name.toLowerCase(Locale.ROOT)))throw invalid("NEST_ACTION_NAME_INVALID");
            if(!row.path("sound").isBoolean()||!row.path("enabled").isBoolean())throw invalid("NEST_ACTION_FLAGS_INVALID");
            boolean enabled=row.get("enabled").asBoolean();
            ObjectNode out=(ObjectNode)content.parse("{}");
            out.put("actionCode",code).put("group",group).put("name",name).put("sound",row.get("sound").asBoolean()).put("enabled",enabled);
            Asset video=attach(row,out,"video",true,assets),first=attach(row,out,"firstFrame",false,assets),last=attach(row,out,"lastFrame",false,assets);
            for(Asset frame:Arrays.asList(first,last))if(video!=null&&frame!=null&&(video.width()!=frame.width()||video.height()!=frame.height()))throw invalid("NEST_FRAME_DIMENSIONS_INVALID");
            out.put("videoCode",video==null?null:"vid_"+video.sha());
            if(publishing && enabled && (video==null||first==null||last==null))throw invalid("NEST_ACTIVE_MEDIA_INCOMPLETE");
            if(!publishing || enabled)result.add(out);
        }
        return result;
    }
    private Asset attach(JsonNode row,ObjectNode out,String key,boolean video,Map<String,Asset> cache) {
        var node=row.get(key+"Id");
        if(node==null||node.isNull()){out.putNull(key+"Id").putNull(key+"Url");return null;}
        String id=node.asText();
        if(!id.matches("[0-9a-f]{32}"))throw invalid("NEST_ASSET_ID_INVALID");
        Asset asset=cache.computeIfAbsent(id,k->{
            var rows=db.query("SELECT url,mime,width,height,sha256 FROM public_operational_upload WHERE id=? AND status='READY'",
                (rs,n)->new Asset(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getString(5)),k);
            if(rows.isEmpty())throw invalid("NEST_ASSET_NOT_READY");
            return rows.getFirst();
        });
        if(video?!asset.mime().equals("video/mp4"):!Set.of("image/webp","image/png","image/jpeg").contains(asset.mime()))throw invalid("NEST_ASSET_KIND_INVALID");
        out.put(key+"Id",id).put(key+"Url",asset.url());
        return asset;
    }
}
