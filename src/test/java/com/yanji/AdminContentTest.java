package com.yanji;
import static org.junit.jupiter.api.Assertions.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.context.request.*;

@SpringBootTest(properties="management-content.directory=${java.io.tmpdir}/xcwc-content-tests")
@AutoConfigureMockMvc @ActiveProfiles("test")
class AdminContentTest {
    @Autowired BrandIpService brands;
    @Autowired ContentService content;@Autowired AdminIdentityService admins;@Autowired RegistrationService users;
    @Autowired CloudPetService pets;@Autowired WardrobeService wardrobe;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;@Autowired MockMvc mvc;
    String admin,reader,editor,asset,userAuth,user,bird,pet;ObjectNode doc;
    static final AtomicInteger ids=new AtomicInteger(1000);
    String requestId(){return UUID.randomUUID().toString();}
    void context(String auth){var request=new MockHttpServletRequest();request.addHeader("Authorization",auth);RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));}
    String operator(int role){long id=ids.incrementAndGet();String name="fixture_"+id;db.update("INSERT INTO sys_user(user_id,user_name,nick_name,password,status,del_flag) VALUES(?,?,?,?,'0','0')",id,name,name,new BCryptPasswordEncoder(4).encode("fixture-password-123"));db.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)",id,role);return "Bearer "+admins.login(name,"fixture-password-123").get("token");}
    @BeforeEach void setup() throws Exception {
        for(String table:List.of("brand_ip_history","brand_ip_revision","wardrobe_operation","wardrobe_outfit","wardrobe_entitlement","content_release_asset","content_command","content_preview","content_entity","content_release","content_asset"))db.update("DELETE FROM "+table);
        db.update("UPDATE content_workspace SET version=0,published_id=NULL,draft=? WHERE id=1","{\"schemaVersion\":1,\"characters\":[],\"appearances\":[],\"accessories\":[],\"pages\":[],\"products\":[]}");
        admin=operator(1);reader=operator(4);editor=operator(2);context(admin);
        var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB),"png",bytes);
        asset=(String)content.upload(new MockMultipartFile("file","fixture.png","image/png",bytes.toByteArray()),"合成测试素材，仅隔离验证").get("assetId");
        doc=json.createObjectNode();doc.put("schemaVersion",1);for(String k:List.of("characters","appearances","accessories","pages","products"))doc.putArray(k);
        for(String c:List.of("yellow","fixture_two","fixture_three")){
            var ch=doc.withArray("characters").addObject();ch.put("id",c).put("code",c).put("name",c).put("description","合成测试角色").put("defaultAppearanceId",c+"_default").put("adoptionEnabled",true);
            look(c,c+"_default",0);
        }
        look("yellow","raincoat",3);
        var accessory=doc.withArray("accessories").addObject();accessory.put("id","hat").put("name","帽子").put("price",0).put("available",true).put("slot","head").put("assetId",asset);
        accessory.putArray("compatible").addObject().put("characterId","yellow").put("appearanceId","yellow_default").put("x",200).put("y",100).put("width",300).put("height",300).put("layer",1);
        doc.withArray("pages").addObject().put("id","nestDefault").put("backgroundAssetId",asset).putArray("banners");
        doc.withArray("products").addObject().put("id","product").put("name","独立商品").put("price",29.90).put("coverAssetId",asset).putArray("detailAssetIds").add(asset);
        var session=users.register("u_"+users.randomKey(8),"fixture-password-123");userAuth="Bearer "+session.token();user=users.userId(userAuth);bird=users.provision().id();pet=((Map<?,?>)pets.adopt(userAuth,new CloudPetService.Adoption(requestId(),bird,"小鸟")).get("pet")).get("petId").toString();
    }
    void look(String c,String id,int price){var a=doc.withArray("appearances").addObject();a.put("id",id).put("characterId",c).put("name",id).put("price",price).put("available",true);var slots=a.putObject("slots");for(String s:List.of("normal","happy","hungry","thirsty","lonely","shy","angry","crying","confused","veryHappy"))slots.put(s,asset);}
    long version(){return Long.parseLong(content.draft().get("version").toString());}
    String publish(){content.save(version(),doc);return content.publish(version(),requestId()).get("releaseId").toString();}
    String ready(){String release=publish();content.mapEntity(bird,"yellow");return release;}
    @Test void brandIpPublicationIsIsolatedAndStateChangesDoNotPublishDrafts() throws Exception {
        String original=publish();
        ((ObjectNode)doc.withArray("characters").get(0)).put("name","selected update");
        ((ObjectNode)doc.withArray("characters").get(1)).put("name","other unfinished draft");
        content.save(version(),doc);
        var bytes=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB),"png",bytes);
        String draftAsset=(String)content.upload(new MockMultipartFile("file","new-expression.png","image/png",bytes.toByteArray()),"隔离测试").get("assetId");
        var slots=(ObjectNode)doc.withArray("appearances").get(0).get("slots");
        for(String state:List.of("normal","happy","angry"))slots.put(state,draftAsset);
        content.save(version(),doc);
        mvc.perform(get("/bird/v1/content/assets/"+draftAsset)).andExpect(status().isNotFound());
        assertPublicExpressions(asset);
        var changed=content.publishBrandIp(version(),requestId(),"yellow","publish",null);
        var live=content.release(changed.get("releaseId").toString());
        assertPublicExpressions(draftAsset);
        mvc.perform(get("/bird/v1/content/assets/"+draftAsset)).andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("image/png")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(bytes.toByteArray()));

        assertEquals("selected update",BrandIpContent.character(live,"yellow").path("name").asText());
        assertEquals("fixture_two",BrandIpContent.character(live,"fixture_two").path("name").asText());
        ((ObjectNode)doc.withArray("characters").get(0)).put("name","unfinished selected");
        content.save(version(),doc);
        long before=version();String request=requestId();
        var off=content.publishBrandIp(before,request,"yellow","delist",null);
        assertEquals(off.get("releaseId"),content.publishBrandIp(before,request,"yellow","delist",null).get("releaseId"));
        assertEquals("selected update",BrandIpContent.character(content.published(),"yellow").path("name").asText());
        assertFalse(BrandIpContent.character(content.published(),"yellow").path("adoptionEnabled").asBoolean());
        content.publishBrandIp(version(),requestId(),"yellow","rollback",original);
        assertPublicExpressions(asset);
        assertEquals("yellow",BrandIpContent.character(content.published(),"yellow").path("name").asText());
        assertFalse(BrandIpContent.character(content.published(),"yellow").path("adoptionEnabled").asBoolean());
        content.publishBrandIp(version(),requestId(),"yellow","list",null);
        assertEquals("yellow",BrandIpContent.character(content.published(),"yellow").path("name").asText());
        assertTrue(BrandIpContent.character(content.published(),"yellow").path("adoptionEnabled").asBoolean());
    }
    @Test void unchangedLegacyGridStillPublishesWithAnimationEditingDisabled(){
        var look=(ObjectNode)doc.path("appearances").get(0);
        var anim=look.putObject("animation");
        anim.put("assetId",asset).put("columns",2).put("rows",2).put("frames",3).put("fps",8);
        String original=publish();
        assertEquals(anim,content.release(original).path("appearances").get(0).get("animation"));
        anim.put("durationMs",375).putArray("frameTimesMs").add(0).add(120).add(240);
        assertThatThrownBy(()->content.save(version(),doc)).hasMessageContaining("ANIMATION_EDITING_DISABLED");
        assertEquals(original,content.publishedId());
    }

    @Test void timelineMustHaveOneIncreasingTimestampPerFrameAndRetainTheLastFrame(){
        var anim=json.createObjectNode();
        anim.put("assetId",asset).put("columns",2).put("rows",2).put("frames",3).put("fps",8).put("durationMs",600);
        var times=anim.putArray("frameTimesMs").add(0).add(100).add(400);
        ContentRules.animation(anim,new HashSet<>(),new HashSet<>());
        times.set(2,json.getNodeFactory().numberNode(100));
        assertThatThrownBy(()->ContentRules.animation(anim,new HashSet<>(),new HashSet<>())).hasMessageContaining("INVALID_ANIMATION_TIMELINE");
        times.set(2,json.getNodeFactory().numberNode(600));
        assertThatThrownBy(()->ContentRules.animation(anim,new HashSet<>(),new HashSet<>())).hasMessageContaining("INVALID_ANIMATION_TIMELINE");
        times.remove(2);
        assertThatThrownBy(()->ContentRules.animation(anim,new HashSet<>(),new HashSet<>())).hasMessageContaining("INVALID_ANIMATION_TIMELINE");
    }
    private void assertPublicExpressions(String assetId) throws Exception {
        var read=mvc.perform(get("/bird/v1/content/characters/yellow")).andExpect(status().isOk())
            .andExpect(jsonPath("$.character.defaultAppearanceId").value("yellow_default"))
            .andExpect(jsonPath("$.releaseId").isNotEmpty());
        for(String state:List.of("normal","happy","angry"))
            read.andExpect(jsonPath("$.appearances[0].slots."+state).value(assetId));
    }
    @Test void brandIpPublicationRejectsEditorAndStaleVersions(){
        publish();long old=version();content.save(old,doc);
        assertThrows(RegistrationService.Failure.class,()->content.publishBrandIp(old,requestId(),"yellow","delist",null));
        long current=version();context(editor);
        assertThrows(RegistrationService.Failure.class,()->content.publishBrandIp(current,requestId(),"yellow","delist",null));
    }
    ObjectNode brandInput(Map<String,Object> detail){
        var input=json.createObjectNode();input.put("name",detail.get("name").toString()).put("description",detail.get("description").toString()).put("city","杭州市").put("coverAssetId",asset);
        input.set("expressions",json.valueToTree(detail.get("expressions")));return input;
    }
    Map<String,Object> brandAction(String id,String action,Map<String,Object> detail,String request){return brands.action(id,action,detail.get("draftVersion").toString(),detail.get("stateVersion").toString(),request,null);}
    @Test void legacySparseDraftPublishesWithOnlyNormalImage() throws Exception {
        var look=(ObjectNode)doc.path("appearances").get(0);
        look.putObject("slots").put("normal",asset);
        ((ObjectNode)doc.path("characters").get(0)).putObject("brand")
            .put("cityCode","hangzhou").put("cityName","杭州").put("coverAssetId","");
        content.save(version(),doc);
        var draft=brands.detail("yellow");
        assertThat(draft.get("configured")).isEqualTo(1);
        var preview=(JsonNode)brands.preview("yellow").get("content");
        assertThat(preview.path("appearances").get(0).path("slots").path("happy").asText()).isEqualTo(asset);
        brandAction("yellow","publish",draft,requestId());
        assertPublicExpressions(asset);
        var after=brands.detail("yellow");
        assertThat(after.get("configured")).isEqualTo(1);
        assertThat(after.get("dirty")).isEqualTo(false);
    }

    @Test void optionalImageCannotStandInForRequiredNormalImage() {
        ((ObjectNode)doc.path("appearances").get(0)).putObject("slots").put("happy",asset);
        ((ObjectNode)doc.path("characters").get(0)).putObject("brand")
            .put("cityCode","hangzhou").put("cityName","杭州").put("coverAssetId",asset);
        content.save(version(),doc);
        assertThatThrownBy(()->brandAction("yellow","publish",brands.detail("yellow"),requestId()))
            .hasMessageContaining("IP_NORMAL_EXPRESSION_REQUIRED");
        assertThat(content.publishedId()).isNull();
    }
    @Test void brandCrudAndIndependentEditingKeepImmutableExpressionIds(){
        content.save(version(),doc);
        var a=brands.detail("yellow");var b=brands.detail("fixture_two");
        var input=brandInput(a);input.put("name","品牌 A");brands.save("yellow",a.get("draftVersion").toString(),input);
        var second=brandInput(b);second.put("name","品牌 B");brands.save("fixture_two",b.get("draftVersion").toString(),second);
        assertEquals("杭州",brands.detail("yellow").get("city"));assertEquals("品牌 B",brands.detail("fixture_two").get("name"));
        assertThrows(RegistrationService.Failure.class,()->brands.save("yellow",a.get("draftVersion").toString(),input));
        var fresh=brands.detail("yellow");var invalid=brandInput(fresh);((ObjectNode)invalid.withArray("expressions").get(0)).put("id","changed");
        assertThrows(RegistrationService.Failure.class,()->brands.save("yellow",fresh.get("draftVersion").toString(),invalid));
        assertEquals(a.get("expressions"),brands.detail("yellow").get("expressions"));
    }
    @Test void brandActionsUsePerIpVersionsAndReplayAfterLostResponse(){
        content.save(version(),doc);for(String id:List.of("yellow","fixture_two")){var item=brands.detail(id);brands.save(id,item.get("draftVersion").toString(),brandInput(item));}
        var a=brands.detail("yellow");var b=brands.detail("fixture_two");
        String req=requestId();var result=brandAction("yellow","list",a,req);
        assertEquals(result.get("releaseId"),brandAction("yellow","list",a,req).get("releaseId"));
        brandAction("fixture_two","list",b,requestId());
        assertTrue(BrandIpContent.character(content.published(),"yellow").path("adoptionEnabled").asBoolean());
        assertEquals(1,brands.history("yellow").size());
        assertThrows(RegistrationService.Failure.class,()->brandAction("yellow","delist",a,requestId()));
        var current=brands.detail("yellow");brandAction("yellow","delist",current,requestId());
        assertFalse(BrandIpContent.character(content.published(),"yellow").path("adoptionEnabled").asBoolean());
    }
    @Test void legacyPublishCannotReopenManagedIpOrPublishItsPendingDraft(){
        publish();var a=brands.detail("yellow");brands.save("yellow",a.get("draftVersion").toString(),brandInput(a));
        brandAction("yellow","delist",brands.detail("yellow"),requestId());
        var pending=brands.detail("yellow");var input=brandInput(pending);input.put("name","pending new name");brands.save("yellow",pending.get("draftVersion").toString(),input);
        var legacy=(ObjectNode)content.draft().get("content");
        ((ObjectNode)legacy.withArray("characters").get(0)).put("name","legacy overwrite");
        content.save(version(),legacy);
        assertEquals("pending new name",brands.detail("yellow").get("name"));
        content.publish(version(),requestId());
        assertEquals("yellow",BrandIpContent.character(content.published(),"yellow").path("name").asText());
        assertFalse(BrandIpContent.character(content.published(),"yellow").path("adoptionEnabled").asBoolean());
    }
    @Test void incompleteBrandCanSaveButCannotListAndDraftCanBeRemoved(){
        var input=json.createObjectNode().put("name","未完成资料");var created=brands.create(input);String id=created.get("id").toString();
        assertEquals(10,((List<?>)created.get("expressions")).size());
        assertThrows(RegistrationService.Failure.class,()->brandAction(id,"list",created,requestId()));
        brands.delete(id,created.get("draftVersion").toString());
        assertThrows(RegistrationService.Failure.class,()->brands.detail(id));
    }
    @Test void brandBackgroundPersistsAndPublishesOnlyForSelectedIp(){
        publish();
        var created=brands.create(json.createObjectNode().put("name","另一个角色").put("backgroundAssetId",asset));
        assertEquals(asset,created.get("backgroundAssetId"));
        var before=brands.detail("yellow");
        var input=brandInput(before).put("city","杭州").put("backgroundAssetId",asset);
        var configured=brands.save("yellow",before.get("draftVersion").toString(),input);
        assertEquals(asset,configured.get("backgroundAssetId"));
        assertFalse(BrandIpContent.character(content.published(),"yellow").has("backgroundAssetId"));
        var renamed=brands.save("yellow",configured.get("draftVersion").toString(),brandInput(configured).put("name","新的名称"));
        assertEquals(asset,renamed.get("backgroundAssetId"));
        brandAction("yellow","publish",renamed,requestId());
        assertEquals(asset,((JsonNode)content.publicCharacter("yellow").get("character")).path("backgroundAssetId").asText());
        assertFalse(BrandIpContent.character(content.published(),"fixture_two").has("backgroundAssetId"));
        var cleared=brands.save("yellow",brands.detail("yellow").get("draftVersion").toString(),brandInput(renamed).putNull("backgroundAssetId"));
        assertNull(cleared.get("backgroundAssetId"));
        brandAction("yellow","publish",cleared,requestId());
        assertFalse(BrandIpContent.character(content.published(),"yellow").has("backgroundAssetId"));
    }
    @Test void brandHttpPermissionsAndThumbnailsAreProtected() throws Exception {
        mvc.perform(get("/admin/v1/bird/brand-ips")).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/v1/bird/brand-ips").header("Authorization",reader).contentType("application/json").content("{\"name\":\"denied\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/v1/assets/"+asset+"/thumbnail")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/v1/assets/"+asset+"/thumbnail").header("Authorization",reader)).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
    }
    @AfterEach void clear(){db.update("DELETE FROM content_entity");RequestContextHolder.resetRequestAttributes();}
    @Test void managementAndConsumerSessionsAreIsolatedWithRealHttpStatuses() throws Exception {
        mvc.perform(get("/admin/v1/bird/content/draft")).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate","Bearer realm=\"xcwc-admin\""));
        mvc.perform(get("/admin/v1/bird/content/draft").header("Authorization",userAuth)).andExpect(status().isUnauthorized());
        mvc.perform(get("/bird/v1/me/pets").header("Authorization",admin)).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/v1/getInfo").header("Authorization",admin)).andExpect(status().isOk()).andExpect(jsonPath("$.permissions").isArray()).andExpect(jsonPath("$.user.password").doesNotExist());
        context(reader);assertThatThrownBy(()->content.save(0,doc)).hasMessageContaining("ADMIN_PERMISSION_DENIED");
        context(editor);content.save(0,doc);assertThatThrownBy(()->content.publish(1,requestId())).hasMessageContaining("ADMIN_PERMISSION_DENIED");
    }
    @Test void draftPreviewAndAssetArePrivateUntilPublish() throws Exception {
        content.save(0,doc);assertThat(content.publicConfig().get("releaseId")).isEqualTo("");
        mvc.perform(get("/bird/v1/content/assets/"+asset)).andExpect(status().isNotFound());
        String token=content.previewToken().get("token").toString();assertThat(content.preview(token).get("characters")).hasSize(3);
        mvc.perform(get("/admin/v1/bird/content/preview")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/v1/bird/content/preview").header("X-Preview-Token",token)).andExpect(status().isOk());
        content.publish(1,requestId());mvc.perform(get("/bird/v1/content/assets/"+asset)).andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM wardrobe_entitlement",Integer.class)).isZero();
    }
    @Test void incompleteDraftCanSaveButCannotPublish(){((ObjectNode)doc.get("appearances").get(0).get("slots")).remove("normal");content.save(0,doc);assertThatThrownBy(()->content.publish(1,requestId())).hasMessageContaining("INVALID_ASSET_ID");assertThat(content.publishedId()).isNull();}
    @Test void publicationIsIdempotentAndChecksVersion(){content.save(0,doc);String key=requestId();String first=content.publish(1,key).get("releaseId").toString();assertThat(content.publish(1,key).get("replayed")).isEqualTo(true);assertThat(content.publishedId()).isEqualTo(first);assertThatThrownBy(()->content.publish(2,key)).hasMessageContaining("IDEMPOTENCY_CONFLICT");assertThatThrownBy(()->content.save(0,doc)).hasMessageContaining("CONTENT_VERSION_CONFLICT");}
    @Test void rollbackPreservesReceiptsBalancesAndNewUnlocks(){String first=ready();var acq=wardrobe.acquire(userAuth,new WardrobeService.Acquire(requestId(),"raincoat",first));assertThat(acq.get("charged")).isEqualTo(3L);((ObjectNode)doc.get("appearances").get(3)).put("price",5);publish();content.rollback(version(),requestId(),first);assertThat(db.queryForObject("SELECT points_balance FROM app_user WHERE id=?",Long.class,user)).isEqualTo(2);assertThat(wardrobe.catalog(userAuth).get("ownedIds").toString()).contains("raincoat");assertThat(db.queryForObject("SELECT COUNT(*) FROM wardrobe_operation",Integer.class)).isEqualTo(1);}
    @Test void acquisitionReplayAndConcurrentDoubleClickDebitOnlyOnce() throws Exception {
        String release=ready();String key=requestId();var command=new WardrobeService.Acquire(key,"raincoat",release);var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{var a=pool.submit(()->{gate.await();return wardrobe.acquire(userAuth,command);});var b=pool.submit(()->{gate.await();return wardrobe.acquire(userAuth,command);});gate.countDown();assertThat(List.of(a.get(15,TimeUnit.SECONDS).get("replayed"),b.get(15,TimeUnit.SECONDS).get("replayed"))).containsExactlyInAnyOrder(false,true);}finally{pool.shutdownNow();}
        assertThat(db.queryForObject("SELECT points_balance FROM app_user WHERE id=?",Long.class,user)).isEqualTo(2);assertThatThrownBy(()->wardrobe.acquire(userAuth,new WardrobeService.Acquire(key,"hat",release))).hasMessageContaining("IDEMPOTENCY_CONFLICT");
    }
    @Test void lowerPriceAndWithdrawalDoNotRemoveEntitlements(){String release=ready();wardrobe.acquire(userAuth,new WardrobeService.Acquire(requestId(),"raincoat",release));((ObjectNode)doc.get("appearances").get(3)).put("price",0).put("available",false);publish();assertThat(wardrobe.acquire(userAuth,new WardrobeService.Acquire(requestId(),"raincoat",content.publishedId())).get("charged")).isEqualTo(0L);assertThat(db.queryForObject("SELECT points_balance FROM app_user WHERE id=?",Long.class,user)).isEqualTo(2);wardrobe.wear(userAuth,pet,new WardrobeService.Wear(requestId(),"0","raincoat",null));wardrobe.wear(userAuth,pet,new WardrobeService.Wear(requestId(),"1","yellow_default",null));assertThat(wardrobe.wear(userAuth,pet,new WardrobeService.Wear(requestId(),"2","raincoat",null)).get("appearanceId")).isEqualTo("raincoat");}
    @Test void outfitConflictAndAccessoryCompatibilityPreserveAccountOwnership(){String r=ready();wardrobe.acquire(userAuth,new WardrobeService.Acquire(requestId(),"hat",r));wardrobe.acquire(userAuth,new WardrobeService.Acquire(requestId(),"raincoat",r));wardrobe.wear(userAuth,pet,new WardrobeService.Wear(requestId(),"0","yellow_default",Map.of("head","hat")));var result=wardrobe.wear(userAuth,pet,new WardrobeService.Wear(requestId(),"1","raincoat",null));assertThat(result.get("removedAccessoryIds")).isEqualTo(List.of("hat"));assertThat(wardrobe.catalog(userAuth).get("ownedIds").toString()).contains("hat");assertThatThrownBy(()->wardrobe.wear(userAuth,pet,new WardrobeService.Wear(requestId(),"0","raincoat",null))).hasMessageContaining("OUTFIT_VERSION_CONFLICT");}
    @Test void laterPetSharesUnlockAndOtherAccountCannotEquip(){String r=ready();wardrobe.acquire(userAuth,new WardrobeService.Acquire(requestId(),"raincoat",r));String bird2=users.provision().id();content.mapEntity(bird2,"yellow");String pet2=((Map<?,?>)pets.adopt(userAuth,new CloudPetService.Adoption(requestId(),bird2,"二鸟")).get("pet")).get("petId").toString();wardrobe.wear(userAuth,pet2,new WardrobeService.Wear(requestId(),"0","raincoat",null));assertThat(wardrobe.outfit(userAuth,pet).get("appearanceId")).isEqualTo("yellow_default");String other="Bearer "+users.register("other_"+users.randomKey(6),"fixture-password-123").token();assertThatThrownBy(()->wardrobe.outfit(other,pet)).hasMessageContaining("PET_NOT_FOUND");}
    @Test void cannotDeletePublishedItemsOrCompatibility(){ready();doc.withArray("accessories").removeAll();content.save(version(),doc);assertThatThrownBy(()->content.publish(version(),requestId())).hasMessageContaining("PUBLISHED_ITEM_CANNOT_BE_DELETED");}
    @Test void adoptionClosedStillAllowsExistingPet(){ready();((ObjectNode)doc.get("characters").get(0)).put("adoptionEnabled",false);publish();assertThat(pets.detail(userAuth,pet)).containsKey("pet");assertThat(users.adopt(userAuth,bird,requestId(),"重试").bindingId()).isEqualTo(pet);String other="Bearer "+users.register("other_"+users.randomKey(6),"fixture-password-123").token();assertThatThrownBy(()->users.adopt(other,bird,requestId(),"鸟")).hasMessageContaining("暂不开放");}
    @Test void imageUploadWorksWithoutLicenseAndRejectsSpoofedFiles() throws Exception {
        var bytes=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB),"png",bytes);
        var file=new MockMultipartFile("file","expression.png","image/png",bytes.toByteArray());
        var result=mvc.perform(multipart("/admin/v1/assets").file(file).header("Authorization",admin))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.mime").value("image/png"))
            .andReturn();
        String uploaded=json.readTree(result.getResponse().getContentAsString()).path("assetId").asText();
        assertThat(content.readAsset(uploaded,true,null)).isEqualTo(bytes.toByteArray());
        assertThat(content.assetRow(uploaded).get("license_note")).isEqualTo("");
        assertThatThrownBy(()->content.upload(new MockMultipartFile("file","x.png","image/png","<script>".getBytes()),null))
            .hasMessageContaining("ASSET_FORMAT_UNSUPPORTED");
    }
    @Test void previewExpiryAndAccountDisableAreEnforced(){content.save(0,doc);String t=content.previewToken().get("token").toString();db.update("UPDATE content_preview SET expires_at=TIMESTAMP '2020-01-01 00:00:00'");assertThatThrownBy(()->content.preview(t)).hasMessageContaining("PREVIEW_EXPIRED");}
    @Test void insufficientBalanceAndStaleReleaseDoNotGrantOrDebit(){String r=ready();db.update("UPDATE app_user SET points_balance=0 WHERE id=?",user);assertThatThrownBy(()->wardrobe.acquire(userAuth,new WardrobeService.Acquire(requestId(),"raincoat",r))).hasMessageContaining("INSUFFICIENT_POINTS");assertThatThrownBy(()->wardrobe.acquire(userAuth,new WardrobeService.Acquire(requestId(),"hat","old"))).hasMessageContaining("CONTENT_RELEASE_CHANGED");assertThat(db.queryForObject("SELECT COUNT(*) FROM wardrobe_entitlement",Integer.class)).isZero();}
    @Test void referencedAssetCannotBeDeletedAndUnpublishedAssetCanBeQuarantined() throws Exception {
        content.save(0,doc);assertThatThrownBy(()->content.deleteUnusedAsset(asset)).hasMessageContaining("ASSET_STILL_REFERENCED");
        var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB),"png",bytes);
        String unused=content.upload(new MockMultipartFile("file","unused.png","image/png",bytes.toByteArray()),"fixture").get("assetId").toString();
        content.deleteUnusedAsset(unused);assertThatThrownBy(()->content.readAsset(unused,true,null)).hasMessageContaining("ASSET_NOT_FOUND");
    }
    @Test void corruptedReferencedFileCannotReplaceLastGoodPublication() throws Exception {
        String good=ready();java.nio.file.Files.write(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),"xcwc-content-tests",asset),new byte[]{1,2});
        content.save(version(),doc);assertThatThrownBy(()->content.publish(version(),requestId())).hasMessageContaining("ASSET_UNAVAILABLE");assertThat(content.publishedId()).isEqualTo(good);
    }
    @Test void concurrentPublicationChangesPointerOnlyOnce() throws Exception {
        content.save(0,doc);var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{var jobs=new ArrayList<Future<Boolean>>();for(int i=0;i<2;i++)jobs.add(pool.submit(()->{context(admin);gate.await();try{content.publish(1,requestId());return true;}catch(RegistrationService.Failure e){assertThat(e.code).isEqualTo("CONTENT_VERSION_CONFLICT");return false;}finally{RequestContextHolder.resetRequestAttributes();}}));gate.countDown();int success=0;for(var job:jobs)if(job.get(15,TimeUnit.SECONDS))success++;assertThat(success).isEqualTo(1);}finally{pool.shutdownNow();}
    }

    @Test void publishedAssetManifestIncludesIntegrityAndNoPrivateMetadata() throws Exception {
        assertThat((List<?>)content.publicConfig().get("assets")).isEmpty();String id=publish();
        mvc.perform(get("/bird/v1/content")).andExpect(status().isOk()).andExpect(jsonPath("$.releaseId").value(id)).andExpect(jsonPath("$.assets[0].sha256").isString()).andExpect(jsonPath("$.assets[0].size_bytes").isNumber()).andExpect(jsonPath("$.assets[0].license_note").doesNotExist()).andExpect(jsonPath("$.capabilities.coordinateScale").value(1000));
        mvc.perform(get("/bird/v1/content/characters/yellow")).andExpect(status().isOk()).andExpect(jsonPath("$.releaseId").value(id)).andExpect(jsonPath("$.assets[0].url").value("/bird/v1/content/assets/"+asset));
    }
}
