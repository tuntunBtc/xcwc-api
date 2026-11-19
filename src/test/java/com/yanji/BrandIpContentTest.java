package com.yanji;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BrandIpContentTest {
    private final ObjectMapper json = new ObjectMapper();

    ObjectNode fixture() {
        var doc = json.createObjectNode();
        doc.put("schemaVersion", 1);
        for (String group : new String[]{"characters", "appearances", "accessories", "pages", "products"}) doc.putArray(group);
        for (String id : new String[]{"a", "b"}) {
            doc.withArray("characters").addObject().put("id", id).put("code", id)
                    .put("name", id).put("defaultAppearanceId", id + "_look").put("adoptionEnabled", false);
            doc.withArray("appearances").addObject().put("id", id + "_look").put("characterId", id).put("name", "old");
        }
        doc.withArray("appearances").addObject().put("id", "historic").put("characterId", "a").put("name", "owned");
        doc.withArray("products").addObject().put("id", "p").put("name", "published");
        return doc;
    }

    @Test void publishesOnlySelectedDefaultLookAndRole() {
        var live = fixture(); var draft = live.deepCopy();
        ((ObjectNode) draft.withArray("characters").get(0)).put("name", "new a");
        ((ObjectNode) draft.withArray("characters").get(1)).put("name", "unfinished b");
        ((ObjectNode) draft.withArray("products").get(0)).put("name", "unfinished product");
        ((ObjectNode) draft.withArray("appearances").get(2)).put("name", "unfinished wardrobe");
        var merged = BrandIpContent.merge(live, draft, "a", true);
        assertEquals("new a", merged.path("characters").get(0).path("name").asText());
        assertEquals(live.path("characters").get(1), merged.path("characters").get(1));
        assertEquals(live.path("products"), merged.path("products"));
        assertEquals(live.path("appearances").get(2), merged.path("appearances").get(2));
        assertTrue(merged.path("characters").get(0).path("adoptionEnabled").asBoolean());
        assertEquals("a", live.path("characters").get(0).path("name").asText());
    }

    @Test void rollbackCannotReopenAdoption() {
        var live = fixture(); var historical = live.deepCopy();
        ((ObjectNode) historical.withArray("characters").get(0)).put("adoptionEnabled", true);
        assertFalse(BrandIpContent.merge(live, historical, "a", null).path("characters").get(0).path("adoptionEnabled").asBoolean());
    }

    @Test void rejectsCrossRoleDefaultLookAndIdentityChanges() {
        var live = fixture(); var draft = live.deepCopy();
        ((ObjectNode) draft.withArray("characters").get(0)).put("defaultAppearanceId", "b_look");
        assertThrows(RegistrationService.Failure.class, () -> BrandIpContent.merge(live, draft, "a", null));
        ((ObjectNode) draft.withArray("characters").get(0)).put("defaultAppearanceId", "a_look").put("code", "changed");
        assertThrows(RegistrationService.Failure.class, () -> BrandIpContent.merge(live, draft, "a", null));
    }
}
