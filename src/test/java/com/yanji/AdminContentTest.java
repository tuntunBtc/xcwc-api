package com.yanji;

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
    @Autowired ContentService content;@Autowired AdminIdentityService admins;@Autowired RegistrationService users;
    @Autowired CloudPetService pets;@Autowired WardrobeService wardrobe;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;@Autowired MockMvc mvc;
    String admin,reader,editor,asset,userAuth,user,bird,pet;ObjectNode doc;
    static final AtomicInteger ids=new AtomicInteger(1000);
    String requestId(){return UUID.randomUUID().toString();}
    void context(String auth){var request=new MockHttpServletRequest();request.addHeader("Authorization",auth);RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));}
    String operator(int role){long id=ids.incrementAndGet();String name="fixture_"+id;db.update("INSERT INTO sys_user(user_id,user_name,nick_name,password,status,del_flag) VALUES(?,?,?,?,'0','0')",id,name,name,new BCryptPasswordEncoder(4).encode("fixture-password-123"));db.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)",id,role);return "Bearer "+admins.login(name,"fixture-password-123").get("token");}
    @BeforeEach void setup() throws Exception {
        for(String table:List.of("wardrobe_operation","wardrobe_outfit","wardrobe_entitlement","content_release_asset","content_command","content_preview","content_entity","content_release","content_asset"))db.update("DELETE FROM "+table);
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
    @AfterEach void clear(){db.update("DELETE FROM content_entity");RequestContextHolder.resetRequestAttributes();}
    @Test void managementAndConsumerSessionsAreIsolatedWithRealHttpStatuses() throws Exception {
        mvc.perform(get("/api/admin/content/draft")).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate","Bearer realm=\"xcwc-admin\""));
        mvc.perform(get("/api/admin/content/draft").header("Authorization",userAuth)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me/pets").header("Authorization",admin)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/getInfo").header("Authorization",admin)).andExpect(status().isOk()).andExpect(jsonPath("$.permissions").isArray()).andExpect(jsonPath("$.user.password").doesNotExist());
        context(reader);assertThatThrownBy(()->content.save(0,doc)).hasMessageContaining("ADMIN_PERMISSION_DENIED");
        context(editor);content.save(0,doc);assertThatThrownBy(()->content.publish(1,requestId())).hasMessageContaining("ADMIN_PERMISSION_DENIED");
    }
    @Test void draftPreviewAndAssetArePrivateUntilPublish() throws Exception {
        content.save(0,doc);assertThat(content.publicConfig().get("releaseId")).isEqualTo("");
        mvc.perform(get("/api/content/assets/"+asset)).andExpect(status().isNotFound());
        String token=content.previewToken().get("token").toString();assertThat(content.preview(token).get("characters")).hasSize(3);
        mvc.perform(get("/api/content/preview")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/content/preview").header("X-Preview-Token",token)).andExpect(status().isOk());
        content.publish(1,requestId());mvc.perform(get("/api/content/assets/"+asset)).andExpect(status().isOk());
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
    @Test void assetValidationRejectsSpoofedAndUnlicensedUploads(){assertThatThrownBy(()->content.upload(new MockMultipartFile("file","x.png","image/png","<script>".getBytes()),"fixture")).hasMessageContaining("ASSET_FORMAT_UNSUPPORTED");assertThatThrownBy(()->content.upload(new MockMultipartFile("file","x.png","image/png",new byte[]{1}),"")).hasMessageContaining("ASSET_LICENSE_REQUIRED");}
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
        mvc.perform(get("/api/content")).andExpect(status().isOk()).andExpect(jsonPath("$.releaseId").value(id)).andExpect(jsonPath("$.assets[0].sha256").isString()).andExpect(jsonPath("$.assets[0].size_bytes").isNumber()).andExpect(jsonPath("$.assets[0].license_note").doesNotExist()).andExpect(jsonPath("$.capabilities.coordinateScale").value(1000));
        mvc.perform(get("/api/content/characters/yellow")).andExpect(status().isOk()).andExpect(jsonPath("$.releaseId").value(id)).andExpect(jsonPath("$.assets[0].url").value("/api/content/assets/"+asset));
    }
}
