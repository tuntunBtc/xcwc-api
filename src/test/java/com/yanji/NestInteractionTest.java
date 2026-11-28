package com.yanji;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.sql.Timestamp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"nest-interactions.enabled=true","spring.datasource.url=jdbc:h2:mem:nestactions;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class NestInteractionTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    String admin;
    String adminUrl="/admin/v1/bird/brand-ips/testrole/nest-interactions",liveUrl="/bird/v1/nest-interactions/testrole";
    String video="a".repeat(32),frame="b".repeat(32),bad="c".repeat(32);
    void asset(String id,String mime,int width,long actor) {
        db.update("INSERT INTO public_operational_upload(id,filename,url,mime,width,height,size_bytes,sha256,actor_id,status) VALUES(?,?,?,?,?,1920,5000,?,?,'READY')",
            id,id,"https://assets.test/"+id,mime,width,"1".repeat(64),actor);
    }
    org.springframework.test.web.servlet.ResultActions save(long version,String action) throws Exception {
        return mvc.perform(put(adminUrl).header("Authorization",admin).contentType("application/json")
            .content("{\"expectedVersion\":"+version+",\"actions\":["+action+"]}"));
    }
    @Test void draftsArePrivateAndAtomicAndUseVerifiedMediaMetadata() throws Exception {
        long actor=963281;
        db.update("INSERT INTO sys_user(user_id,user_name,nick_name,password,status,del_flag,create_time) VALUES(?, 'nest_ops','ops','unused','0','0',CURRENT_TIMESTAMP)",actor);
        db.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,1)",actor);
        String token="adm_"+"1".repeat(64);admin="Bearer "+token;
        db.update("INSERT INTO admin_session(token_hash,user_id,expires_at) VALUES(?,?,?)",RegistrationService.digest(token),actor,Timestamp.from(Instant.now().plusSeconds(3600)));
        String role="{\"characters\":[{\"id\":\"testrole\"}]}";
        db.update("UPDATE content_workspace SET draft=?,published_id='testrel' WHERE id=1",role);
        db.update("INSERT INTO content_release(id,workspace_version,payload,actor_id,created_at) VALUES('testrel',1,?,?,CURRENT_TIMESTAMP)",role,actor);
        asset(video,"video/mp4",1080,actor);asset(frame,"image/png",1080,actor);asset(bad,"image/png",100,actor);
        String action="""
            {"actionCode":"eat","name":"吃饭","group":"food","sound":false,"enabled":true,"videoId":"%s","firstFrameId":"%s","lastFrameId":"%s","videoUrl":"https://spoof.test/untrusted"}
            """.formatted(video,frame,frame);
        mvc.perform(get(adminUrl)).andExpect(status().isUnauthorized());
        mvc.perform(get(adminUrl).header("Authorization",admin)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(0));
        save(0,action.replace(frame,bad)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NEST_FRAME_DIMENSIONS_INVALID"));
        save(0,action.replace(video,"d".repeat(32))).andExpect(status().isBadRequest());
        save(0,action).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.actions[0].videoUrl").value("https://assets.test/"+video));
        mvc.perform(get(liveUrl)).andExpect(status().isNotFound());
        save(0,action).andExpect(status().isConflict());
        mvc.perform(post(adminUrl+"/publish").header("Authorization",admin).contentType("application/json").content("{\"expectedVersion\":1}")).andExpect(status().isOk());
        mvc.perform(get(liveUrl)).andExpect(status().isOk()).andExpect(jsonPath("$.actions[0].videoCode").value("vid_"+"1".repeat(64)));
        save(1,action.replace("true","false")).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        mvc.perform(get(liveUrl)).andExpect(jsonPath("$.actions[0].enabled").value(true));
        mvc.perform(post(adminUrl+"/publish").header("Authorization",admin).contentType("application/json").content("{\"expectedVersion\":2}")).andExpect(status().isOk());
        mvc.perform(get(liveUrl)).andExpect(jsonPath("$.actions.length()").value(0)).andExpect(jsonPath("$.version").value(2));
    }
}
