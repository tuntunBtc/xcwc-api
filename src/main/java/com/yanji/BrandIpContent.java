package com.yanji;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Builds one-IP releases from the latest publication, never from the shared draft. */
final class BrandIpContent {
    static final List<String> STATES = List.of("normal", "happy", "hungry", "thirsty",
            "lonely", "shy", "angry", "crying", "confused", "veryHappy");

    private BrandIpContent() {}

    static JsonNode character(JsonNode document, String id) {
        return ContentRules.index(document, "characters", 500).get(id);
    }

    static String activeExpression(JsonNode look, String state) {
        var metadata = look.path("expressions").path(state);
        if (!metadata.path("listed").asBoolean(true)) return "";
        return metadata.has("assetId") ? metadata.path("assetId").asText() : look.path("slots").path(state).asText();
    }

    static void fillOptionalExpressions(ObjectNode look) {
        String normal = activeExpression(look, "normal");
        if (normal.isBlank()) return;
        var slots = look.objectNode();
        for (String state : STATES) {
            String asset = activeExpression(look, state);
            slots.put(state, asset.isBlank() ? normal : asset);
        }
        look.set("slots", slots);
    }

    static ObjectNode publishableDraft(JsonNode draft, String id) {
        ObjectNode snapshot = draft.deepCopy();
        var role = character(snapshot, id);
        if (role == null) throw ContentRules.fail("CHARACTER_NOT_FOUND");
        var look = (ObjectNode) ContentRules.index(snapshot, "appearances", 5000)
            .get(role.path("defaultAppearanceId").asText());
        if (look == null || activeExpression(look, "normal").isBlank()) {
            throw ContentRules.fail("IP_NORMAL_EXPRESSION_REQUIRED");
        }
        fillOptionalExpressions(look);
        if (role.path("brand").isObject()) {
            ((ObjectNode)role.path("brand")).put("coverAssetId", activeExpression(look, "normal"));
        }
        return snapshot;
    }

    static ObjectNode merge(JsonNode published, JsonNode source, String id, Boolean enabled) {
        var selected = character(source, id);
        if (selected == null) throw ContentRules.fail("CHARACTER_NOT_FOUND");
        ObjectNode result = published.deepCopy();
        ObjectNode role = selected.deepCopy();
        var previous = character(published, id);
        // Content rollback/update must never restore an historical operational flag.
        role.put("adoptionEnabled", enabled != null ? enabled
                : previous != null && previous.path("adoptionEnabled").asBoolean());
        replace(result, "characters", role);
        String lookId = role.path("defaultAppearanceId").asText();
        var look = ContentRules.index(source, "appearances", 5000).get(lookId);
        if (look == null || !look.path("characterId").asText().equals(id))
            throw ContentRules.fail("FREE_DEFAULT_APPEARANCE_REQUIRED");
        ObjectNode selectedLook=look.deepCopy();
        var previousLook=ContentRules.index(published,"appearances",5000).get(lookId);
        if(previousLook!=null&&previousLook.has("expressions")&&!selectedLook.has("expressions")){
            var expressions=selectedLook.putObject("expressions");
            for(int i=0;i<STATES.size();i++){
                String state=STATES.get(i);
                expressions.putObject(state).put("id",previousLook.path("expressions").path(state).path("id").asText()).put("name",BrandIpService.NAMES.get(i));
            }
        }
        replace(result, "appearances", selectedLook);
        // Historical wardrobe and other IPs remain exactly as currently published.
        ContentRules.preserve(published, result);
        return result;
    }

    static void replace(ObjectNode document, String group, JsonNode value) {
        var array = document.withArray(group);
        for (int i = 0; i < array.size(); i++) {
            if (array.get(i).path("id").equals(value.path("id"))) {
                array.set(i, value.deepCopy());
                return;
            }
        }
        array.add(value.deepCopy());
    }

    static ObjectNode pinManaged(JsonNode candidate, JsonNode live, List<String> managed) {
        ObjectNode result = candidate.deepCopy();
        for (String id : managed) {
            for (String group : List.of("characters", "appearances")) {
                String key = group.equals("characters") ? "id" : "characterId";
                var array = result.withArray(group);
                for (int i = array.size() - 1; i >= 0; i--)
                    if (array.get(i).path(key).asText().equals(id)) array.remove(i);
                for (var item : live.path(group))
                    if (item.path(key).asText().equals(id)) array.add(item.deepCopy());
            }
        }
        return result;
    }
}
