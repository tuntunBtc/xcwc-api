package com.yanji;

import com.ruoyi.system.mapper.SysUserMapper;
import com.ruoyi.system.mapper.SysMenuMapper;
import com.ruoyi.common.core.domain.entity.SysUser;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** RuoYi user/menu model adapter. Consumer and administrator sessions never share tables. */
@Service
public class AdminIdentityService implements AdminAccess {
    private final JdbcTemplate db;
    private final SysUserMapper users;
    private final SysMenuMapper menus;
    private final RegistrationService keys;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(12);
    private final String dummy = bcrypt.encode("not-a-user-password");
    @Value("${admin.bootstrap-password:}") private String bootstrap;
    public AdminIdentityService(JdbcTemplate db, SysUserMapper users, SysMenuMapper menus, RegistrationService keys) {
        this.db=db;this.users=users;this.menus=menus;this.keys=keys;
    }
    private RegistrationService.Failure error(HttpStatus status,String code){return new RegistrationService.Failure(status,code,code);}
    static String tokenHash(String auth) {
        if(auth==null || !auth.matches("Bearer adm_[0-9a-f]{64}")) return "";
        return RegistrationService.digest(auth.substring(7));
    }
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        if(bootstrap.isBlank())return;
        password(bootstrap);
        // Only creates the first operator; restarts never reset an existing password.
        if(db.queryForObject("SELECT COUNT(*) FROM sys_user",Integer.class)==0) {
            db.update("INSERT INTO sys_user(user_id,user_name,nick_name,password,status,del_flag,create_time) VALUES(1,'admin','运营管理员',?,'0','0',CURRENT_TIMESTAMP)",bcrypt.encode(bootstrap));
            db.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(1,1)");
        }
    }
    private void password(String value) {
        if(value==null||value.length()<12||value.getBytes(StandardCharsets.UTF_8).length>72)
            throw error(HttpStatus.BAD_REQUEST,"ADMIN_PASSWORD_INVALID");
    }
    @Transactional(noRollbackFor=RegistrationService.Failure.class)
    public Map<String,Object> login(String username,String password) {
        if(username==null||!username.matches("[a-zA-Z0-9_-]{3,30}")||password==null||password.length()>72)
            throw error(HttpStatus.UNAUTHORIZED,"ADMIN_LOGIN_REQUIRED");
        String nameHash=RegistrationService.digest(username.toLowerCase(Locale.ROOT));
        try{db.update("INSERT INTO admin_login_attempt(name_hash,failures) VALUES(?,0)",nameHash);}catch(org.springframework.dao.DuplicateKeyException ignored){}
        var attempt=db.queryForMap("SELECT failures,locked_until FROM admin_login_attempt WHERE name_hash=? FOR UPDATE",nameHash);
        Instant now=Instant.now();Timestamp until=(Timestamp)attempt.get("locked_until");
        if(until!=null&&until.toInstant().isAfter(now))throw error(HttpStatus.TOO_MANY_REQUESTS,"ADMIN_LOGIN_LOCKED");
        SysUser user=users.selectUserByUserName(username);
        boolean matches=bcrypt.matches(password,user==null?dummy:user.getPassword());
        if(!matches||user==null||!"0".equals(user.getStatus())||!"0".equals(user.getDelFlag())){
            int count=until!=null?1:((Number)attempt.get("failures")).intValue()+1;
            db.update("UPDATE admin_login_attempt SET failures=?,locked_until=? WHERE name_hash=?",count,count>=5?Timestamp.from(now.plusSeconds(600)):null,nameHash);
            throw error(HttpStatus.UNAUTHORIZED,"ADMIN_LOGIN_REQUIRED");
        }
        db.update("UPDATE admin_login_attempt SET failures=0,locked_until=NULL WHERE name_hash=?",nameHash);
        db.update("DELETE FROM admin_session WHERE expires_at<CURRENT_TIMESTAMP");
        String token="adm_"+keys.randomKey(32);
        db.update("INSERT INTO admin_session(token_hash,user_id,expires_at) VALUES(?,?,?)",RegistrationService.digest(token),user.getUserId(),Timestamp.from(now.plusSeconds(28800)));
        db.update("UPDATE sys_user SET login_date=CURRENT_TIMESTAMP WHERE user_id=?",user.getUserId());
        return Map.of("code",200,"token",token,"expiresAt",now.plusSeconds(28800));
    }
    private SysUser current() {
        var attributes=RequestContextHolder.getRequestAttributes();
        String auth=attributes instanceof ServletRequestAttributes a?a.getRequest().getHeader("Authorization"):null;
        var ids=db.queryForList("SELECT s.user_id FROM admin_session s JOIN sys_user u ON u.user_id=s.user_id WHERE s.token_hash=? AND s.expires_at>CURRENT_TIMESTAMP AND u.status='0' AND u.del_flag='0'",Long.class,tokenHash(auth));
        if(ids.isEmpty())throw error(HttpStatus.UNAUTHORIZED,"ADMIN_LOGIN_REQUIRED");
        return users.selectUserById(ids.getFirst());
    }
    private Set<String> permissions(SysUser user){return new HashSet<>(menus.selectMenuPermsByUserId(user.getUserId()));}
    public Actor require(String permission) {
        SysUser user=current();
        if(!permissions(user).contains(permission))throw error(HttpStatus.FORBIDDEN,"ADMIN_PERMISSION_DENIED");
        return new Actor(user.getUserId(),user.getUserName());
    }
    public Map<String,Object> info(){
        SysUser u=current();
        return Map.of("code",200,"user",Map.of("userId",u.getUserId().toString(),"userName",u.getUserName(),"nickName",u.getNickName(),"avatar",""),"roles",u.getRoles().stream().map(r->r.getRoleKey()).toList(),"permissions",permissions(u));
    }
    public void logout(String auth){db.update("DELETE FROM admin_session WHERE token_hash=?",tokenHash(auth));}
    public List<Map<String,Object>> accounts(){require("system:manage");var rows=db.queryForList("SELECT user_id,user_name,nick_name,status FROM sys_user ORDER BY user_id LIMIT 100");for(var r:rows)r.put("roles",db.queryForList("SELECT role_id FROM sys_user_role WHERE user_id=?",Long.class,r.get("user_id")));return rows;}
    public Map<String,Object> account(long id){require("system:manage");var rows=db.queryForList("SELECT user_id,user_name,nick_name,status FROM sys_user WHERE user_id=?",id);if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"ADMIN_NOT_FOUND");var row=rows.getFirst();row.put("roles",db.queryForList("SELECT role_id FROM sys_user_role WHERE user_id=?",Long.class,id));return row;}
    @Transactional public long createAccount(String name,String nickname,String secret,List<Long> roles){
        Actor actor=require("system:manage");password(secret);
        if(name==null||!name.matches("[a-zA-Z0-9_-]{3,30}")||nickname==null||nickname.isBlank()||nickname.length()>30||roles==null||roles.isEmpty()||roles.size()>4||!Set.of(1L,2L,3L,4L).containsAll(roles))throw error(HttpStatus.BAD_REQUEST,"INVALID_ACCOUNT");
        if(users.selectUserByUserName(name)!=null)throw error(HttpStatus.CONFLICT,"ADMIN_USERNAME_TAKEN");
        SysUser u=new SysUser();u.setUserName(name);u.setNickName(nickname);u.setPassword(bcrypt.encode(secret));u.setStatus("0");u.setCreateBy(actor.username());
        users.insertUser(u);
        for(long role:new HashSet<>(roles))db.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)",u.getUserId(),role);
        audit(actor,"CREATE_ACCOUNT",u.getUserId().toString());return u.getUserId();
    }
    @Transactional public void setRoles(long id,List<Long> roles,String status){
        Actor a=require("system:manage");
        if(id==1||id==a.id())throw error(HttpStatus.CONFLICT,"CANNOT_DISABLE_SELF_OR_OWNER");
        if(!Set.of("0","1").contains(status)||roles==null||roles.isEmpty()||roles.size()>4||!Set.of(1L,2L,3L,4L).containsAll(roles))throw error(HttpStatus.BAD_REQUEST,"INVALID_ACCOUNT");
        if(db.update("UPDATE sys_user SET status=? WHERE user_id=?",status,id)==0)throw error(HttpStatus.NOT_FOUND,"ADMIN_NOT_FOUND");
        db.update("DELETE FROM sys_user_role WHERE user_id=?",id);
        for(long role:new HashSet<>(roles))db.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)",id,role);
        db.update("DELETE FROM content_preview WHERE actor_id=?",id);db.update("DELETE FROM admin_session WHERE user_id=?",id);audit(a,"ACCOUNT_ROLES",Long.toString(id));
    }
    @Transactional public void changePassword(String old,String secret){
        SysUser u=current();password(secret);
        if(old==null||!bcrypt.matches(old,u.getPassword()))throw error(HttpStatus.BAD_REQUEST,"CURRENT_PASSWORD_INCORRECT");
        db.update("UPDATE sys_user SET password=?,pwd_update_date=CURRENT_TIMESTAMP WHERE user_id=?",bcrypt.encode(secret),u.getUserId());
        db.update("DELETE FROM admin_session WHERE user_id=?",u.getUserId());audit(new Actor(u.getUserId(),u.getUserName()),"PASSWORD_CHANGED",u.getUserId().toString());
    }
    public void audit(Actor actor,String operation,String target){
        db.update("INSERT INTO sys_oper_log(title,business_type,oper_name,oper_param,status,oper_time) VALUES(?,2,?,?,0,CURRENT_TIMESTAMP)",operation,actor.username(),target);
    }
}
