package com.yanji;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class CheckinService {
    public record Submission(String requestId, List<String> birdIds, String styleId, String label) {}
    public record Work(String id, String status, List<String> birdIds, String styleId, String label,
                       Instant createdAt, String imageUrl, String originalUrl, int reward) {}
    public record Page(List<Work> items, String nextCursor, long total) {}
    public record Character(String key, String typeId, String cityCode, String skinId, String name) {}
    public record Picture(byte[] bytes, String type) {}
    private record Row(Work work, String fingerprint, String original, String result, String originalType) {}
    private final JdbcTemplate jdbc;
    private final RegistrationService registration;
    private final PointsService points;
    private final Clock clock;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final Path storage;
    public CheckinService(JdbcTemplate jdbc, RegistrationService registration, PointsService points, Clock clock,
                          ObjectMapper json, PlatformTransactionManager manager,
                          @Value("${app.checkins.directory:./data/checkins}") String directory) {
        this.jdbc=jdbc; this.registration=registration; this.points=points; this.clock=clock; this.json=json;
        this.tx=new TransactionTemplate(manager); this.tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED); this.storage=Path.of(directory).toAbsolutePath().normalize();
    }
    private RegistrationService.Failure fail(HttpStatus status, String code, String message) {
        return new RegistrationService.Failure(status,code,message);
    }
    public List<Character> catalog() {
        return jdbc.query("SELECT DISTINCT type_id,city_code,skin_id,type_name FROM bird ORDER BY city_code,type_id,skin_id",
            (r,n)->new Character(RegistrationService.digest(r.getString(1)+":"+r.getString(2)+":"+r.getString(3)),
                r.getString(1),r.getString(2),r.getString(3),r.getString(4)));
    }
    private Row row(java.sql.ResultSet r, int n) throws java.sql.SQLException {
        String id=r.getString("id");
        return new Row(new Work(id,"SUCCEEDED",List.of(r.getString("bird_ids").split(",")),r.getString("style_id"),
            r.getString("label"),r.getTimestamp("created_at").toInstant(),"/api/checkins/"+id+"/image",
            "/api/checkins/"+id+"/original",r.getInt("reward_amount")),r.getString("fingerprint"),r.getString("original_path"),
            r.getString("result_path"),r.getString("original_type"));
    }
    private Row owned(String user,String id) {
        var rows=jdbc.query("SELECT * FROM checkin_work WHERE user_id=? AND id=?",this::row,user,id);
        if(rows.isEmpty())throw fail(HttpStatus.NOT_FOUND,"CHECKIN_NOT_FOUND","未找到你的合照");
        return rows.getFirst();
    }
    public Work find(String auth,String id) { return owned(registration.userId(auth),id).work(); }
    public Picture image(String auth,String id,boolean original) {
        Row row=owned(registration.userId(auth),id);
        try { return new Picture(Files.readAllBytes(storage.resolve(original?row.original():row.result())),original?row.originalType():"image/jpeg"); }
        catch(IOException e) { throw fail(HttpStatus.SERVICE_UNAVAILABLE,"CHECKIN_FILE_UNAVAILABLE","作品文件暂时不可用，请稍后再试"); }
    }
    public Page history(String auth,String cursor,int limit) {
        String user=registration.userId(auth);
        if(limit<1||limit>50)throw fail(HttpStatus.BAD_REQUEST,"INVALID_PAGE","每页数量应为 1～50");
        List<Row> rows;
        if(cursor==null||cursor.isBlank()) rows=jdbc.query("SELECT * FROM checkin_work WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT ?",this::row,user,limit+1);
        else {
            try {
                if(cursor.length()>160)throw new IllegalArgumentException();
                String decoded=new String(Base64.getUrlDecoder().decode(cursor),java.nio.charset.StandardCharsets.UTF_8);
                String[] parts=decoded.split(":");
                if(parts.length!=2||!parts[1].matches("[a-f0-9]{32}"))throw new IllegalArgumentException();
                Timestamp time=Timestamp.from(Instant.ofEpochMilli(Long.parseLong(parts[0])));
                rows=jdbc.query("SELECT * FROM checkin_work WHERE user_id=? AND (created_at<? OR (created_at=? AND id<?)) ORDER BY created_at DESC,id DESC LIMIT ?",this::row,user,time,time,parts[1],limit+1);
            } catch(IllegalArgumentException e) { throw fail(HttpStatus.BAD_REQUEST,"INVALID_CURSOR","分页游标格式不正确"); }
        }
        boolean more=rows.size()>limit;var items=rows.stream().limit(limit).map(Row::work).toList();String next=null;
        if(more){Work last=items.getLast();next=Base64.getUrlEncoder().withoutPadding().encodeToString((last.createdAt().toEpochMilli()+":"+last.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        return new Page(items,next,jdbc.queryForObject("SELECT COUNT(*) FROM checkin_work WHERE user_id=?",Long.class,user));
    }
    private byte[] validateImage(MultipartFile file,boolean result) {
        if(file==null||file.isEmpty()||file.getSize()>(result?8L:15L)*1024*1024)
            throw fail(HttpStatus.BAD_REQUEST,"INVALID_IMAGE","原照片最多 15 MB，成品最多 8 MB，且不能为空");
        try {
            byte[] bytes=file.getBytes();
            try(ImageInputStream in=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers=ImageIO.getImageReaders(in);if(!readers.hasNext())throw new IOException();
                var reader=readers.next();
                try {
                    reader.setInput(in);String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                    int w=reader.getWidth(0),h=reader.getHeight(0);
                    if(!Set.of("jpeg","jpg","png").contains(format)||w<1||h<1||(long)w*h>24_000_000L||
                       (result&&(!Set.of("jpeg","jpg").contains(format)||w!=1000||h!=1010)))throw new IOException();
                    BufferedImage decoded=reader.read(0);if(decoded==null)throw new IOException();
                } finally { reader.dispose(); }
            }
            return bytes;
        } catch(IOException|RuntimeException e) { throw fail(HttpStatus.BAD_REQUEST,"INVALID_IMAGE","图片内容无法读取，或尺寸超出限制；请更换照片"); }
    }
    private String sha(byte[]... parts) {
        try {var digest=MessageDigest.getInstance("SHA-256");for(byte[] p:parts){digest.update(java.nio.ByteBuffer.allocate(4).putInt(p.length).array());digest.update(p);}return HexFormat.of().formatHex(digest.digest());}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private final java.util.concurrent.Semaphore uploads=new java.util.concurrent.Semaphore(2);
    public record Creation(Work work, boolean created) {}
    public Work create(String auth,Submission request,MultipartFile original,MultipartFile result) {
        return createResponse(auth,request,original,result).work();
    }
    public Creation createResponse(String auth,Submission request,MultipartFile original,MultipartFile result) {
        if(!uploads.tryAcquire())throw fail(HttpStatus.TOO_MANY_REQUESTS,"CHECKIN_BUSY","照片处理中，请稍后原样重试");
        try{return createAccepted(auth,request,original,result);}finally{uploads.release();}
    }
    private Creation createAccepted(String auth,Submission request,MultipartFile original,MultipartFile result) {
        String user=registration.userId(auth);
        if(request==null||request.requestId()==null||!request.requestId().matches("[A-Za-z0-9_-]{16,64}")||
           request.birdIds()==null||request.birdIds().isEmpty()||request.birdIds().size()>3||
           new HashSet<>(request.birdIds()).size()!=request.birdIds().size()||
           request.birdIds().stream().anyMatch(id->id==null||!id.matches("[a-f0-9]{32}"))||
           !Set.of("natural","film","soft","mono").contains(Objects.toString(request.styleId(),""))||
           request.label()==null||request.label().length()>20)
            throw fail(HttpStatus.BAD_REQUEST,"INVALID_CHECKIN","请选择 1～3 只不同的小鸟，并检查地点和风格");
        // Use the real entity IDs returned by /me/birds; catalogue entries never convey ownership.
        Set<String> mine=new HashSet<>(registration.mine(auth).stream().filter(RegistrationService.Bird::active).map(RegistrationService.Bird::id).toList());
        if(!mine.containsAll(request.birdIds()))throw fail(HttpStatus.NOT_FOUND,"OWNED_BIRD_NOT_FOUND","只能选择你已领养的小鸟");
        byte[] source=validateImage(original,false),output=validateImage(result,true);
        String contentHash=sha(source);
        String fingerprint;
        try {fingerprint=sha(json.writeValueAsBytes(request),source,output);}catch(IOException e){throw new IllegalStateException(e);}
        String id=UUID.randomUUID().toString().replace("-","");String sourceFile=id+"-original",resultFile=id+"-result";
        boolean keep=false;
        try {
            Files.createDirectories(storage);
            Files.write(storage.resolve(sourceFile),source,StandardOpenOption.CREATE_NEW);
            Files.write(storage.resolve(resultFile),output,StandardOpenOption.CREATE_NEW);
            Work work=tx.execute(status->{
                jdbc.queryForObject("SELECT points_balance FROM app_user WHERE id=? FOR UPDATE",Long.class,user);
                var prior=jdbc.query("SELECT * FROM checkin_work WHERE user_id=? AND request_id=? FOR UPDATE",this::row,user,request.requestId());
                if(!prior.isEmpty()){
                    if(!prior.getFirst().fingerprint().equals(fingerprint))throw fail(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","同一请求编号不能用于不同合照");
                    return prior.getFirst().work();
                }
                if(!jdbc.queryForList("SELECT id FROM points_ledger WHERE user_id=? AND request_id=? FOR UPDATE",user,request.requestId()).isEmpty())
                    throw fail(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","请求编号已用于其他积分操作");
                Instant now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
                String type=(source[0]==(byte)0x89)?"image/png":"image/jpeg";
                jdbc.update("INSERT INTO checkin_work(id,user_id,request_id,fingerprint,bird_ids,style_id,label,original_path,result_path,original_type,created_at,source_hash,reward_amount) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id,user,request.requestId(),fingerprint,String.join(",",request.birdIds()),request.styleId(),request.label().isBlank()?"在路上":request.label(),sourceFile,resultFile,type,Timestamp.from(now),contentHash,0);
                boolean legacyReady=backfillSources(user);
                LocalDate date=now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
                var days=jdbc.queryForList("SELECT rewarded FROM checkin_reward_day WHERE user_id=? AND reward_date=? FOR UPDATE",user,date);
                int count;
                if(days.isEmpty()) {
                    Instant from=date.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
                    Instant to=date.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
                    count=jdbc.query("SELECT reward_amount FROM checkin_work WHERE user_id=? AND created_at>=? AND created_at<? AND id<>? FOR UPDATE",
                        (r,n)->r.getInt(1),user,Timestamp.from(from),Timestamp.from(to),id).stream().mapToInt(Integer::intValue).sum();
                    jdbc.update("INSERT INTO checkin_reward_day(user_id,reward_date,rewarded) VALUES(?,?,?)",user,date,count);
                } else count=((Number)days.getFirst().get("rewarded")).intValue();
                boolean seen=!jdbc.queryForList("SELECT source_hash FROM checkin_reward_source WHERE user_id=? AND source_hash=? FOR UPDATE",user,contentHash).isEmpty();
                int reward=legacyReady&&!seen&&count<3?1:0;
                jdbc.update("UPDATE checkin_work SET source_hash=?,reward_amount=? WHERE id=?",contentHash,reward,id);
                if(reward==1) {
                    jdbc.update("INSERT INTO checkin_reward_source(user_id,source_hash) VALUES(?,?)",user,contentHash);
                    jdbc.update("UPDATE checkin_reward_day SET rewarded=rewarded+1 WHERE user_id=? AND reward_date=?",user,date);
                    points.checkinReward(user,id,request.requestId());
                }
                return owned(user,id).work();
            });
            keep=Objects.requireNonNull(work).id().equals(id);return new Creation(work,keep);
        } catch(IOException e){throw fail(HttpStatus.SERVICE_UNAVAILABLE,"CHECKIN_SAVE_FAILED","图片保存未完成，未增加叽分，请重试");}
        finally {if(!keep){delete(sourceFile);delete(resultFile);}}
    }
    private boolean backfillSources(String user) {
        boolean complete=true;
        var old=jdbc.queryForList("SELECT id,original_path,reward_amount FROM checkin_work WHERE user_id=? AND source_hash IS NULL FOR UPDATE",user);
        for(var row:old) {
            try {
                String hash=sha(Files.readAllBytes(storage.resolve((String)row.get("original_path"))));
                jdbc.update("UPDATE checkin_work SET source_hash=? WHERE id=?",hash,row.get("id"));
                if(((Number)row.get("reward_amount")).intValue()>0 && jdbc.queryForList("SELECT source_hash FROM checkin_reward_source WHERE user_id=? AND source_hash=? FOR UPDATE",user,hash).isEmpty())
                    jdbc.update("INSERT INTO checkin_reward_source(user_id,source_hash) VALUES(?,?)",user,hash);
            } catch(IOException e) { complete=false; }
        }
        // Missing legacy originals never grant a speculative reward; saving still succeeds.
        return complete;
    }
    private void delete(String file){try{Files.deleteIfExists(storage.resolve(file));}catch(IOException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Checkin staging file requires cleanup: {}",file);}}
}
