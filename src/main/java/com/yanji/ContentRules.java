package com.yanji;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.http.HttpStatus;

/** Versioned content schema; accepts resource references, never executable layout/code. */
final class ContentRules {
    static RegistrationService.Failure fail(String code){return new RegistrationService.Failure(HttpStatus.BAD_REQUEST,code,code);}
    static void fields(JsonNode n,String... allowed){
        if(n==null||!n.isObject())throw fail("CONTENT_OBJECT_REQUIRED");
        Set<String> keys=Set.of(allowed);n.fieldNames().forEachRemaining(k->{if(!keys.contains(k))throw fail("UNKNOWN_CONTENT_FIELD: "+k);});
    }
    static String string(JsonNode n,String key,int max){
        var v=n.get(key);if(v==null||!v.isTextual()||v.asText().isBlank()||v.asText().length()>max||v.asText().codePoints().anyMatch(Character::isISOControl))throw fail("INVALID_CONTENT_FIELD: "+key);
        return v.asText();
    }
    static String id(JsonNode n,String key){String s=string(n,key,32);if(!s.matches("[A-Za-z0-9_-]{1,32}"))throw fail("INVALID_CONTENT_ID");return s;}
    static List<JsonNode> array(JsonNode n,String key,int max){var a=n.get(key);if(a==null||!a.isArray()||a.size()>max)throw fail("INVALID_CONTENT_ARRAY: "+key);List<JsonNode> list=new ArrayList<>();a.forEach(list::add);return list;}
    static Map<String,JsonNode> index(JsonNode root,String key,int max){Map<String,JsonNode> map=new LinkedHashMap<>();for(var n:array(root,key,max))if(map.put(id(n,"id"),n)!=null)throw fail("DUPLICATE_CONTENT_ID");return map;}
    static void integer(JsonNode n,String key,int min,int max){var v=n.get(key);if(v==null||!v.isIntegralNumber()||!v.canConvertToInt()||v.intValue()<min||v.intValue()>max)throw fail("INVALID_CONTENT_NUMBER: "+key);}
    static void bool(JsonNode n,String key){if(!n.path(key).isBoolean())throw fail("INVALID_CONTENT_BOOLEAN: "+key);}
    static void asset(JsonNode n,Set<String> assets){if(n==null||!n.isTextual()||!n.asText().matches("[0-9a-f]{32}"))throw fail("INVALID_ASSET_ID");assets.add(n.asText());}
    static Set<String> validate(JsonNode root){
        Set<String> audio=new LinkedHashSet<>();
        var refs=imageReferences(root,audio);
        refs.addAll(audio);
        return refs;
    }

    static void animation(JsonNode a,Set<String> images,Set<String> audio){
        fields(a,"assetId","columns","rows","frames","fps","frameTimesMs","durationMs","audioAssetId");
        asset(a.get("assetId"),images);
        integer(a,"columns",1,64);integer(a,"rows",1,64);integer(a,"frames",1,512);integer(a,"fps",1,30);
        int frames=a.get("frames").intValue();
        if(frames>a.get("columns").intValue()*a.get("rows").intValue())throw fail("INVALID_ANIMATION_GRID");
        if(a.has("durationMs")||a.has("frameTimesMs")||a.has("audioAssetId")){
            integer(a,"durationMs",1,120000);
            var times=array(a,"frameTimesMs",512);
            if(times.size()!=frames||!times.getFirst().isIntegralNumber()||times.getFirst().asLong()!=0)
                throw fail("INVALID_ANIMATION_TIMELINE");
            int prior=-1;
            for(var time:times){
                if(!time.isIntegralNumber()||!time.canConvertToInt()||time.intValue()<=prior||time.intValue()>=a.get("durationMs").intValue())
                    throw fail("INVALID_ANIMATION_TIMELINE");
                prior=time.intValue();
            }
            if(a.hasNonNull("audioAssetId"))asset(a.get("audioAssetId"),audio);
        }
    }

    static Set<String> imageReferences(JsonNode root,Set<String> audio){
        fields(root,"schemaVersion","characters","appearances","accessories","pages","products");integer(root,"schemaVersion",1,1);
        var characters=index(root,"characters",500);var looks=index(root,"appearances",5000);var extras=index(root,"accessories",5000);var pages=index(root,"pages",4);var products=index(root,"products",3000);
        Set<String> all=new HashSet<>(looks.keySet());for(String id:extras.keySet())if(!all.add(id))throw fail("DUPLICATE_WARDROBE_ID");
        Set<String> assets=new LinkedHashSet<>();Set<String> codes=new HashSet<>();
        for(var n:characters.values()){
            fields(n,"id","code","name","description","defaultAppearanceId","backgroundAssetId","adoptionEnabled","brand");
            if(!codes.add(id(n,"code")))throw fail("DUPLICATE_CHARACTER_CODE");string(n,"name",60);if(n.has("description")&&!n.path("description").asText().isEmpty())string(n,"description",500);bool(n,"adoptionEnabled");
            if(n.has("brand")){var b=n.get("brand");fields(b,"cityCode","cityName","coverAssetId");string(b,"cityCode",20);string(b,"cityName",60);asset(b.get("coverAssetId"),assets);}
            String defaultId=id(n,"defaultAppearanceId");var look=looks.get(defaultId);
            if(look==null||!look.path("characterId").asText().equals(n.path("id").asText())||look.path("price").asInt(-1)!=0||!look.path("available").asBoolean())throw fail("FREE_DEFAULT_APPEARANCE_REQUIRED");
            if(n.hasNonNull("backgroundAssetId"))asset(n.get("backgroundAssetId"),assets);
        }
        for(var n:looks.values()){
            fields(n,"id","characterId","name","price","available","slots","animation","expressions");string(n,"name",60);integer(n,"price",0,1000000);bool(n,"available");
            if(!characters.containsKey(id(n,"characterId")))throw fail("CHARACTER_NOT_FOUND");
            var slots=n.get("slots");fields(slots,"normal","happy","hungry","thirsty","lonely","shy","angry","crying","confused","veryHappy");
            for(String slot:List.of("normal","happy","hungry","thirsty","lonely","shy","angry","crying","confused","veryHappy"))asset(slots.get(slot),assets);
            if(n.has("expressions")){var expressions=n.get("expressions");fields(expressions,BrandIpContent.STATES.toArray(String[]::new));for(String state:BrandIpContent.STATES){var e=expressions.get(state);fields(e,"id","name","assetId","listed");id(e,"id");string(e,"name",60);if(e.has("listed"))bool(e,"listed");if(!e.path("assetId").asText().isEmpty())asset(e.get("assetId"),new HashSet<>());}}
            if(n.hasNonNull("animation"))animation(n.get("animation"),assets,audio);
        }
        for(var n:extras.values()){
            fields(n,"id","name","slot","price","available","assetId","compatible");string(n,"name",60);id(n,"slot");integer(n,"price",0,1000000);bool(n,"available");asset(n.get("assetId"),assets);
            Set<String> pairs=new HashSet<>();var fits=array(n,"compatible",5000);if(fits.isEmpty())throw fail("ACCESSORY_COMPATIBILITY_REQUIRED");
            for(var f:fits){
                fields(f,"characterId","appearanceId","x","y","width","height","layer");var look=looks.get(id(f,"appearanceId"));
                if(look==null||!look.path("characterId").asText().equals(id(f,"characterId")))throw fail("INVALID_ACCESSORY_COMPATIBILITY");
                if(!pairs.add(f.get("appearanceId").asText()))throw fail("DUPLICATE_COMPATIBILITY");
                for(String key:List.of("x","y"))integer(f,key,-1000,1000);
                for(String key:List.of("width","height"))integer(f,key,1,2000);integer(f,"layer",-10,10);
            }
        }
        for(var n:pages.values()){
            fields(n,"id","backgroundAssetId","banners");if(!Set.of("nestDefault","city","shop","profile").contains(n.get("id").asText()))throw fail("UNKNOWN_PAGE");
            if(n.hasNonNull("backgroundAssetId"))asset(n.get("backgroundAssetId"),assets);
            for(var a:array(n,"banners",10))asset(a,assets);
        }
        for(var n:products.values()){
            fields(n,"id","name","coverAssetId","price","detailAssetIds");string(n,"name",100);asset(n.get("coverAssetId"),assets);
            if(!n.path("price").isNumber()||n.get("price").decimalValue().signum()<0||n.get("price").decimalValue().scale()>2||n.get("price").decimalValue().compareTo(new java.math.BigDecimal("999999.99"))>0)throw fail("INVALID_PRODUCT_PRICE");
            var details=array(n,"detailAssetIds",50);if(details.isEmpty())throw fail("PRODUCT_DETAILS_REQUIRED");for(var a:details)asset(a,assets);
        }
        return assets;
    }
    static void preserve(JsonNode before,JsonNode after){
        for(String group:List.of("characters","appearances","accessories")){
            var next=index(after,group,5000);
            for(var old:before.path(group)){
                var now=next.get(old.path("id").asText());
                if(now==null)throw fail("PUBLISHED_ITEM_CANNOT_BE_DELETED");
                for(String k:group.equals("characters")?List.of("code","defaultAppearanceId"):group.equals("appearances")?List.of("characterId"):List.of("slot"))
                    if(!old.path(k).equals(now.path(k)))throw fail("PUBLISHED_IDENTITY_CANNOT_CHANGE");
                if(group.equals("accessories"))for(var fit:old.path("compatible")){
                    boolean exists=false;for(var f:now.path("compatible"))if(f.path("appearanceId").equals(fit.path("appearanceId"))&&f.path("characterId").equals(fit.path("characterId")))exists=true;
                    if(!exists)throw fail("EXISTING_COMPATIBILITY_MUST_REMAIN");
                }
                if(group.equals("appearances")&&old.has("expressions"))for(String state:BrandIpContent.STATES){
                    if(!old.path("expressions").path(state).path("id").equals(now.path("expressions").path(state).path("id")))throw fail("EXPRESSION_ID_IMMUTABLE");
                }
            }
        }
    }
}
