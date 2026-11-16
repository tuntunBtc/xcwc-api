package com.yanji;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CloudPetService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final RegistrationService users;
    private final ObjectMapper json;
    private final Clock clock;
    public CloudPetService(JdbcTemplate jdbc, RegistrationService users, ObjectMapper json, Clock clock) {
        this.jdbc=jdbc; this.users=users; this.json=json; this.clock=clock;
    }
    public record Command(String requestId, String expectedVersion, String action, String nickname, String expectedDate) {}
    public record Adoption(String requestId, String birdId, String nickname) {}
    private RegistrationService.Failure fail(HttpStatus status,String code) {
        return new RegistrationService.Failure(status,code,code);
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.SECONDS); }
    private Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
    private Instant instant(ResultSet r,String key) throws SQLException {
        Timestamp value=r.getTimestamp(key); return value==null?null:value.toInstant();
    }
    private String lock(String auth) {
        String user=users.userId(auth);
        jdbc.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE",String.class,user);
        users.userId(auth); // Revalidate after waiting for another device's transaction.
        return user;
    }
    private void requestId(String id) {
        if(id==null||id.startsWith("system_")||!id.matches("[A-Za-z0-9_-]{16,64}")) throw fail(HttpStatus.BAD_REQUEST,"INVALID_REQUEST_ID");
    }
    private String nickname(String value) {
        if(value==null)throw fail(HttpStatus.BAD_REQUEST,"INVALID_NAME");
        String n=value.strip();int count=n.codePointCount(0,n.length());
        if(count<1||count>6||n.codePoints().anyMatch(Character::isISOControl))throw fail(HttpStatus.BAD_REQUEST,"INVALID_NAME");
        return n;
    }
    private String hash(Object value) {
        try {return RegistrationService.digest(json.writeValueAsString(value));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private byte[] key(String id){return id.getBytes(StandardCharsets.US_ASCII);}
    private Map<String,Object> replay(String user,String id,String digest) {
        var rows=jdbc.queryForList("SELECT payload_hash,result_json FROM pet_operation WHERE user_id=? AND request_id=?",user,key(id));
        if(rows.isEmpty())return null;
        if(!digest.equals(rows.getFirst().get("payload_hash")))throw fail(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT");
        return decode((String)rows.getFirst().get("result_json"));
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> decode(String value) {
        try {var result=json.readValue(value,LinkedHashMap.class);result.put("replayed",true);return result;}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private Map<String,Object> record(String user,String id,String digest,Map<String,Object> result,Instant at) {
        result.put("requestId",id);result.put("replayed",false);
        try {jdbc.update("INSERT INTO pet_operation(user_id,request_id,payload_hash,result_json,created_at) VALUES(?,?,?,?,?)",
            user,key(id),digest,json.writeValueAsString(result),ts(at));}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
        return result;
    }
    private Map<String,Object> binding(String user,String pet) {
        var rows=jdbc.queryForList("SELECT bb.*,b.active,b.bird_code FROM bird_binding bb JOIN bird b ON b.id=bb.bird_id WHERE bb.user_id=? AND bb.id=? FOR UPDATE",user,pet);
        if(rows.isEmpty())throw fail(HttpStatus.NOT_FOUND,"PET_NOT_FOUND");
        return rows.getFirst();
    }
    private void initialize(String pet,Instant at) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM pet_state WHERE binding_id=?",Integer.class,pet)>0)return;
        jdbc.update("INSERT INTO pet_state(binding_id,body_at,mood_at,companion_at) VALUES(?,?,?,?)",pet,ts(at),ts(at),ts(at));
        inventory(pet,"system_initial_v1","INITIAL",3,3,3,3,at);
    }
    private void inventory(String pet,String id,String action,int food,int water,int foodAfter,int waterAfter,Instant at) {
        jdbc.update("INSERT INTO pet_inventory_ledger(binding_id,request_id,operation,food_delta,water_delta,food_after,water_after,created_at) VALUES(?,?,?,?,?,?,?,?)",
            pet,id,action,food,water,foodAfter,waterAfter,ts(at));
    }
    private static class State {
        long version;int food,water,petCount,feedCount,waterCount,cleanCount;
        boolean first;LocalDate date;Instant due,lastPet;PetDecay.State care;
    }
    private State state(String pet,Instant at) {
        State s=jdbc.queryForObject("SELECT * FROM pet_state WHERE binding_id=? FOR UPDATE",(r,n)->{
            State x=new State();x.version=r.getLong("version");x.food=r.getInt("food");x.water=r.getInt("water");
            x.petCount=r.getInt("pet_count");x.feedCount=r.getInt("feed_count");x.waterCount=r.getInt("water_count");x.cleanCount=r.getInt("clean_count");
            x.first=r.getBoolean("first_companion");x.date=r.getObject("mood_date",LocalDate.class);
            x.due=instant(r,"poop_due_at");x.lastPet=instant(r,"last_mood_pet_at");
            x.care=new PetDecay.State(r.getInt("satiety"),r.getInt("hydration"),r.getInt("mood"),instant(r,"body_at"),instant(r,"mood_at"),instant(r,"companion_at"));return x;
        },pet);
        s.care=PetDecay.settle(s.care,at);
        LocalDate date=at.atZone(ZONE).toLocalDate();
        if(!date.equals(s.date)){s.date=date;s.first=false;s.petCount=0;s.feedCount=0;s.waterCount=0;s.cleanCount=0;}
        return s;
    }
    private void save(String pet,State s) {
        var c=s.care;
        jdbc.update("UPDATE pet_state SET version=?,satiety=?,hydration=?,mood=?,food=?,water=?,body_at=?,mood_at=?,companion_at=?,poop_due_at=?,mood_date=?,first_companion=?,pet_count=?,feed_count=?,water_count=?,clean_count=?,last_mood_pet_at=? WHERE binding_id=?",
            s.version,c.satiety(),c.hydration(),c.mood(),s.food,s.water,ts(c.bodyAt()),ts(c.moodAt()),ts(c.companionAt()),ts(s.due),s.date,s.first,s.petCount,s.feedCount,s.waterCount,s.cleanCount,ts(s.lastPet),pet);
    }
    private boolean claimed(String user,LocalDate date) {
        // An old local-supply receipt consumes today's eligibility but never creates cloud inventory.
        return jdbc.queryForObject("SELECT COUNT(*) FROM pet_supply_claim WHERE user_id=? AND claim_date=?",Integer.class,user,date)>0
            ||jdbc.queryForObject("SELECT COUNT(*) FROM wx_supply_claim WHERE user_id=? AND claim_date=?",Integer.class,user,date)>0;
    }
    private Map<String,Object> view(String user,String pet,Map<String,Object> b,State s,Instant at) {
        var c=s.care;Map<String,Object> v=new LinkedHashMap<>();
        v.put("petId",pet);v.put("birdId",b.get("bird_id"));v.put("birdCode",b.get("bird_code"));v.put("nickname",b.get("nickname"));
        var characters=jdbc.queryForList("SELECT character_id FROM content_entity WHERE bird_id=?",String.class,b.get("bird_id"));
        v.put("characterId",characters.isEmpty()?null:characters.getFirst());
        v.put("contentVersion",jdbc.queryForObject("SELECT published_id FROM content_workspace WHERE id=1",String.class));
        v.put("active",b.get("active"));v.put("version",Long.toString(s.version));v.put("ruleVersion","normal-v1");
        v.put("serverTime",at);v.put("businessDate",at.atZone(ZONE).toLocalDate());
        v.put("care",Map.of("satiety",c.satiety(),"hydration",c.hydration(),"mood",c.mood(),"lastCompanionAt",c.companionAt()));
        v.put("inventory",Map.of("food",s.food,"water",s.water));
        Map<String,Object> event=new LinkedHashMap<>();event.put("type","POOP");event.put("dueAt",s.due);
        event.put("status",s.due==null?"NONE":s.due.isAfter(at)?"PENDING":"PRESENT");v.put("cleaning",event);
        v.put("supplyClaimed",claimed(user,at.atZone(ZONE).toLocalDate()));return v;
    }
    private Map<String,Object> wallet(String user) {
        return jdbc.queryForObject("SELECT points_balance,state_version FROM app_user WHERE id=?",(r,n)->Map.of("balance",r.getLong(1),"accountVersion",Long.toString(r.getLong(2))),user);
    }
    private void requireInitialized(String user) {
        boolean enabled=Boolean.TRUE.equals(jdbc.queryForObject("SELECT cloud_pet_enabled FROM app_user WHERE id=?",Boolean.class,user));
        int missing=jdbc.queryForObject("SELECT COUNT(*) FROM bird_binding b LEFT JOIN pet_state s ON s.binding_id=b.id WHERE b.user_id=? AND s.binding_id IS NULL",Integer.class,user);
        if(!enabled||missing>0)throw fail(HttpStatus.CONFLICT,"CLOUD_INITIALIZATION_REQUIRED");
    }
    // Convergent account operation: retries only fill missing trusted bindings, never reset state.
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> initializeAccount(String auth) {
        String user=lock(auth);Instant at=now();
        for(String pet:jdbc.queryForList("SELECT id FROM bird_binding WHERE user_id=? ORDER BY created_at,id",String.class,user))initialize(pet,at);
        jdbc.update("UPDATE app_user SET cloud_pet_enabled=TRUE WHERE id=? AND cloud_pet_enabled=FALSE",user);
        return list(auth);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> list(String auth) {
        String user=lock(auth);Instant at=now();
        requireInitialized(user);
        var pets=new ArrayList<Map<String,Object>>();
        for(String pet:jdbc.queryForList("SELECT id FROM bird_binding WHERE user_id=? ORDER BY created_at,id",String.class,user)) {
            var b=binding(user,pet);pets.add(view(user,pet,b,state(pet,at),at));
        }
        return Map.of("userId",user,"serverTime",at,"items",pets,"wallet",wallet(user));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> detail(String auth,String pet) {
        String user=lock(auth);var b=binding(user,pet);Instant at=now();
        requireInitialized(user);
        return Map.of("pet",view(user,pet,b,state(pet,at),at),"wallet",wallet(user));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> adopt(String auth,Adoption input) {
        if(input.birdId()==null||!input.birdId().matches("[0-9a-f]{32}"))throw fail(HttpStatus.BAD_REQUEST,"INVALID_BIRD_ID");
        requestId(input.requestId());String name=nickname(input.nickname());String user=lock(auth);
        String digest=hash(List.of("ADOPT",input.birdId(),name));var prior=replay(user,input.requestId(),digest);if(prior!=null)return prior;
        // The legacy binding endpoint remains compatible. Cloud request IDs have their own namespace.
        var adopted=users.adopt(auth,input.birdId(),"cloud_"+users.randomKey(16),name);
        boolean created=jdbc.queryForObject("SELECT COUNT(*) FROM pet_state WHERE binding_id=?",Integer.class,adopted.bindingId())==0;
        Instant at=now();initialize(adopted.bindingId(),at);
        jdbc.update("UPDATE app_user SET cloud_pet_enabled=TRUE,state_version=state_version+1 WHERE id=?",user);
        var result=new LinkedHashMap<String,Object>();result.put("outcome","SUCCEEDED");result.put("created",created);
        result.put("pet",view(user,adopted.bindingId(),binding(user,adopted.bindingId()),state(adopted.bindingId(),at),at));result.put("wallet",wallet(user));
        return record(user,input.requestId(),digest,result,at);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> act(String auth,String pet,Command cmd) {
        requestId(cmd.requestId());
        if(cmd.action()==null||!Set.of("RENAME","FEED","WATER","PET","CLEAN","CLAIM_SUPPLY").contains(cmd.action()))throw fail(HttpStatus.BAD_REQUEST,"INVALID_ACTION");
        if(cmd.expectedVersion()==null||!cmd.expectedVersion().matches("[0-9]{1,18}"))throw fail(HttpStatus.BAD_REQUEST,"INVALID_VERSION");
        if(!cmd.action().equals("RENAME")&&cmd.nickname()!=null)throw fail(HttpStatus.BAD_REQUEST,"INVALID_INPUT");
        if(!cmd.action().equals("CLAIM_SUPPLY")&&cmd.expectedDate()!=null)throw fail(HttpStatus.BAD_REQUEST,"INVALID_INPUT");
        String name=cmd.action().equals("RENAME")?nickname(cmd.nickname()):null;
        String user=lock(auth);String digest=hash(Arrays.asList(pet,cmd.expectedVersion(),cmd.action(),name,cmd.expectedDate()));
        var prior=replay(user,cmd.requestId(),digest);if(prior!=null)return prior;
        var b=binding(user,pet);if(!Boolean.TRUE.equals(b.get("active")))throw fail(HttpStatus.GONE,"BIRD_INACTIVE");
        Instant at=now();initialize(pet,at);State s=state(pet,at);
        if(s.version!=Long.parseLong(cmd.expectedVersion()))throw fail(HttpStatus.CONFLICT,"PET_VERSION_CONFLICT");
        int foodBefore=s.food,waterBefore=s.water,reward=0;boolean companion=false,changed=false;int moodGain=0;String outcome="SUCCEEDED";
        var c=s.care;int satiety=c.satiety(),hydration=c.hydration();
        switch(cmd.action()) {
            case "RENAME" -> {changed=!name.equals(b.get("nickname"));jdbc.update("UPDATE bird_binding SET nickname=? WHERE id=?",name,pet);b.put("nickname",name);}
            case "FEED" -> {
                if(satiety==100){outcome="ALREADY_FULL";break;}
                if(s.food==0)throw fail(HttpStatus.CONFLICT,"NO_FOOD");
                s.food--;satiety=Math.min(100,satiety+30);companion=true;changed=true;
                if(s.feedCount<2){moodGain=5;s.feedCount++;}if(s.due==null)s.due=at.plusSeconds(60);
            }
            case "WATER" -> {
                if(hydration==100){outcome="ALREADY_FULL";break;}
                if(s.water==0)throw fail(HttpStatus.CONFLICT,"NO_WATER");
                s.water--;hydration=Math.min(100,hydration+30);companion=true;changed=true;
                if(s.waterCount<2){moodGain=5;s.waterCount++;}
            }
            case "CLEAN" -> {
                // Cleaning is companionship even when the pending event cannot yet be removed.
                companion=true;changed=true;if(s.cleanCount<1){moodGain=5;s.cleanCount++;}
                if(s.due!=null&&!s.due.isAfter(at))s.due=null;
            }
            case "PET" -> {
                companion=true;changed=true;
                if(s.petCount<3&&(s.lastPet==null||!s.lastPet.plusSeconds(30).isAfter(at))){moodGain=10;s.petCount++;s.lastPet=at;}
                reward=petReward(user,(String)b.get("bird_id"),at);
            }
            case "CLAIM_SUPPLY" -> {
                if(!s.date.toString().equals(cmd.expectedDate()))throw fail(HttpStatus.CONFLICT,"SUPPLY_DAY_CHANGED");
                if(claimed(user,s.date)){outcome="ALREADY_CLAIMED";break;}
                if(s.food==Integer.MAX_VALUE||s.water==Integer.MAX_VALUE)throw fail(HttpStatus.CONFLICT,"INVENTORY_FULL");
                s.food++;s.water++;changed=true;
                jdbc.update("INSERT INTO pet_supply_claim(user_id,claim_date,binding_id,request_id,created_at) VALUES(?,?,?,?,?)",user,s.date,pet,cmd.requestId(),ts(at));
            }
            default -> throw new IllegalStateException();
        }
        if(companion&&!s.first){moodGain+=10;s.first=true;}
        s.care=new PetDecay.State(satiety,hydration,Math.min(100,c.mood()+moodGain),c.bodyAt(),companion?at:c.moodAt(),companion?at:c.companionAt());
        s.version++;save(pet,s);
        jdbc.update("UPDATE app_user SET cloud_pet_enabled=TRUE,state_version=state_version+1 WHERE id=?",user);
        if(s.food!=foodBefore||s.water!=waterBefore)inventory(pet,cmd.requestId(),cmd.action(),s.food-foodBefore,s.water-waterBefore,s.food,s.water,at);
        var result=new LinkedHashMap<String,Object>();result.put("outcome",outcome);result.put("changed",changed);result.put("reward",reward);
        result.put("pet",view(user,pet,b,s,at));result.put("wallet",wallet(user));
        return record(user,cmd.requestId(),digest,result,at);
    }
    private int petReward(String user,String bird,Instant at) {
        var account=jdbc.queryForMap("SELECT points_balance,pet_reward_date,pet_reward_count,last_pet_reward_at FROM app_user WHERE id=?",user);
        LocalDate date=at.atZone(ZONE).toLocalDate();Object oldDate=account.get("pet_reward_date");
        int count=oldDate!=null&&date.toString().equals(oldDate.toString())?((Number)account.get("pet_reward_count")).intValue():0;
        Timestamp last=(Timestamp)account.get("last_pet_reward_at");
        if(count>=10||(last!=null&&last.toInstant().plusSeconds(30).isAfter(at)))return 0;
        long balance=Math.addExact(((Number)account.get("points_balance")).longValue(),1);
        jdbc.update("UPDATE app_user SET points_balance=?,pet_reward_date=?,pet_reward_count=?,last_pet_reward_at=? WHERE id=?",balance,date,count+1,ts(at),user);
        jdbc.update("INSERT INTO points_ledger(user_id,request_id,operation,target_id,delta,balance_after,created_at) VALUES(?,?,?,?,?,?,?)",user,"cloud_"+users.randomKey(16),"PET",bird,1,balance,ts(at));
        return 1;
    }
    public Map<String,Object> operation(String auth,String id) {
        requestId(id);String user=users.userId(auth);
        var rows=jdbc.queryForList("SELECT result_json FROM pet_operation WHERE user_id=? AND request_id=?",String.class,user,key(id));
        if(rows.isEmpty())throw fail(HttpStatus.NOT_FOUND,"OPERATION_NOT_FOUND");return decode(rows.getFirst());
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public List<Map<String,Object>> inventoryHistory(String auth,String pet,long before) {
        String user=lock(auth);binding(user,pet);
        return jdbc.query("SELECT * FROM pet_inventory_ledger WHERE binding_id=? AND id<? ORDER BY id DESC LIMIT 50",(r,n)->Map.<String,Object>of(
            "id",r.getString("id"),"requestId",r.getString("request_id"),"operation",r.getString("operation"),"foodDelta",r.getInt("food_delta"),"waterDelta",r.getInt("water_delta"),"foodAfter",r.getInt("food_after"),"waterAfter",r.getInt("water_after"),"createdAt",r.getTimestamp("created_at").toInstant()),pet,before);
    }
    public List<Map<String,Object>> claims(String auth,LocalDate before) {
        String user=users.userId(auth);
        return jdbc.query("SELECT * FROM pet_supply_claim WHERE user_id=? AND claim_date<? ORDER BY claim_date DESC LIMIT 50",(r,n)->Map.<String,Object>of(
            "date",r.getObject("claim_date",LocalDate.class),"petId",r.getString("binding_id"),"requestId",r.getString("request_id"),"food",1,"water",1,"createdAt",r.getTimestamp("created_at").toInstant()),user,before);
    }
}
