package com.yanji;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PublicUploadService {
    static final int MAX_BYTES=50*1024*1024;
    public static final String READ_PATH="/bird/v1/oss/uploads/";
    private final AdminAccess access;
    private final ObjectProvider<PublicUploadStore> stores;
    private final ObjectProvider<PublicUploadVerifier> verifiers;
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final String publicBase;
    private final Path directory;
    private final boolean videoEnabled;
    private final PublicUploadVideoProbe videos;
    private final Semaphore uploadSlots=new Semaphore(1),readSlots=new Semaphore(2),verificationReadSlots=new Semaphore(1);
    public PublicUploadService(AdminAccess access,ObjectProvider<PublicUploadStore> stores,ObjectProvider<PublicUploadVerifier> verifiers,
            JdbcTemplate db,PlatformTransactionManager transactionManager,PublicUploadVideoProbe videos,
            @Value("${management-upload.public-base-url:}") String publicBase,@Value("${management-upload.enabled:false}") boolean enabled,
            @Value("${management-upload.video-enabled:false}") boolean videoEnabled,
            @Value("${management-upload.directory:${java.io.tmpdir}}") String directory) throws IOException {
        this.access=access;this.stores=stores;this.verifiers=verifiers;this.db=db;this.videos=videos;
        this.transactions=new TransactionTemplate(transactionManager);
        this.publicBase=publicBase.isBlank()&&!enabled?"":checkedOrigin(publicBase);
        this.videoEnabled=videoEnabled;this.directory=Path.of(directory);Files.createDirectories(this.directory);
    }
    static String checkedOrigin(String value) {
        URI base = URI.create(value);
        if (!"https".equals(base.getScheme()) || base.getHost() == null || base.getUserInfo() != null
            || base.getQuery() != null || base.getFragment() != null
            || !Set.of("", "/").contains(base.getPath())
            || (base.getPort() != -1 && (base.getPort() < 1 || base.getPort() > 65535)) || base.getHost().toLowerCase(Locale.ROOT).contains("-internal.")) {
            throw new IllegalArgumentException("Public upload base must be an external HTTPS origin");
        }
        return base.toString().replaceAll("/+$", "");
    }

    public record Uploaded(String id,String url,String mime,int width,int height,long size,String sha256) {}
    record Media(String mime,String extension,int width,int height) {}
    public record Metadata(String filename,String mime,String sha256,long size,boolean ready) {}
    public static final class ReadFile implements AutoCloseable {
        private final Path path;private final Semaphore slot;private final AtomicBoolean closed=new AtomicBoolean();
        ReadFile(Path path,Semaphore slot){this.path=path;this.slot=slot;}
        public Path path(){return path;}
        @Override public void close() throws IOException {if(closed.compareAndSet(false,true)){try{Files.deleteIfExists(path);}finally{slot.release();}}}
    }
    public Uploaded upload(MultipartFile file) {
        var actor=access.require("content:edit");var store=stores.getIfAvailable();var verifier=verifiers.getIfAvailable();
        if(store==null||verifier==null||publicBase.isEmpty())throw failure(HttpStatus.SERVICE_UNAVAILABLE,"OSS_UPLOAD_NOT_CONFIGURED");
        if(!uploadSlots.tryAcquire())throw failure(HttpStatus.TOO_MANY_REQUESTS,"OSS_UPLOAD_BUSY");
        Path input=null,readback=null;
        try {
            if(file==null||file.isEmpty())throw failure(HttpStatus.BAD_REQUEST,"PUBLIC_UPLOAD_FILE_REQUIRED");
            if(file.getSize()>MAX_BYTES)throw failure(HttpStatus.PAYLOAD_TOO_LARGE,"PUBLIC_UPLOAD_SIZE_INVALID");
            input=temporary();
            try(var source=file.getInputStream();var target=Files.newOutputStream(input)){PublicUploadFiles.copy(source,target,MAX_BYTES);}
            if(Files.size(input)==0)throw failure(HttpStatus.BAD_REQUEST,"PUBLIC_UPLOAD_FILE_REQUIRED");
            Media media=inspect(input);
            String id=UUID.randomUUID().toString().replace("-", "");String filename=id+"."+media.extension();String url=publicBase+READ_PATH+filename;
            var result=new Uploaded(id,url,media.mime(),media.width(),media.height(),Files.size(input),PublicUploadFiles.sha256(input));
            transactions.executeWithoutResult(status->db.update("INSERT INTO public_operational_upload(id,filename,url,mime,width,height,size_bytes,sha256,actor_id,status) VALUES(?,?,?,?,?,?,?,?,?,'UPLOADING')",
                id,filename,url,media.mime(),media.width(),media.height(),result.size(),result.sha256(),actor.id()));
            try {
                store.putFile(filename,input,media.mime());readback=temporary();
                if(!store.downloadTo(filename,readback)||Files.size(readback)!=result.size()||!PublicUploadFiles.sha256(readback).equals(result.sha256()))throw new IOException("OSS readback mismatch");
                Files.deleteIfExists(readback);readback=null;
                transactions.executeWithoutResult(status->db.update("UPDATE public_operational_upload SET status='VERIFYING',verification_expires_at=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(300)),id));
                verifier.verifyFile(url,input,media.mime());
                transactions.executeWithoutResult(status->{db.update("UPDATE public_operational_upload SET status='READY',verification_expires_at=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",id);audit(actor,result,0);});
                return result;
            }catch(IOException|RuntimeException error){
                try{transactions.executeWithoutResult(status->{db.update("UPDATE public_operational_upload SET status='FAILED',verification_expires_at=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",id);audit(actor,result,1);});}
                catch(RuntimeException bookkeeping){LoggerFactory.getLogger(getClass()).warn("Public upload {} failure bookkeeping unavailable",id);}
                LoggerFactory.getLogger(getClass()).warn("Public upload {} failed at storage or public verification",id);
                throw failure(HttpStatus.SERVICE_UNAVAILABLE,"OSS_UPLOAD_FAILED");
            }
        }catch(PublicUploadFiles.LimitExceeded e){throw failure(HttpStatus.PAYLOAD_TOO_LARGE,"PUBLIC_UPLOAD_SIZE_INVALID");}
        catch(IOException e){throw failure(HttpStatus.SERVICE_UNAVAILABLE,"PUBLIC_UPLOAD_TEMPORARY_STORAGE_FAILED");}
        finally{remove(input);remove(readback);uploadSlots.release();}
    }
    public Metadata metadata(String filename) {
        try{PublicUploadStore.checkedFilename(filename);}catch(IllegalArgumentException e){throw failure(HttpStatus.NOT_FOUND,"PUBLIC_UPLOAD_NOT_FOUND");}
        var rows=db.queryForList("SELECT mime,sha256,size_bytes,status FROM public_operational_upload WHERE filename=? AND (status='READY' OR (status='VERIFYING' AND verification_expires_at>CURRENT_TIMESTAMP))",filename);
        if(rows.isEmpty())throw failure(HttpStatus.NOT_FOUND,"PUBLIC_UPLOAD_NOT_FOUND");
        var row=rows.getFirst();long size=((Number)row.get("size_bytes")).longValue();
        if(size<1||size>MAX_BYTES)throw failure(HttpStatus.SERVICE_UNAVAILABLE,"OSS_PUBLIC_READ_FAILED");
        return new Metadata(filename,(String)row.get("mime"),(String)row.get("sha256"),size,"READY".equals(row.get("status")));
    }
    public ReadFile read(Metadata metadata) {
        var store=stores.getIfAvailable();if(store==null)throw failure(HttpStatus.SERVICE_UNAVAILABLE,"OSS_UPLOAD_NOT_CONFIGURED");
        Semaphore slot=metadata.ready()?readSlots:verificationReadSlots;
        if(!slot.tryAcquire())throw failure(HttpStatus.SERVICE_UNAVAILABLE,"OSS_PUBLIC_READ_BUSY");
        Path target=null;
        try {
            target=temporary();
            if(!store.downloadTo(metadata.filename(),target)||Files.size(target)!=metadata.size()||!PublicUploadFiles.sha256(target).equals(metadata.sha256()))throw new IOException("Public object integrity mismatch");
            return new ReadFile(target,slot);
        }catch(IOException|RuntimeException e){remove(target);slot.release();throw failure(HttpStatus.SERVICE_UNAVAILABLE,"OSS_PUBLIC_READ_FAILED");}
    }
    private Path temporary() throws IOException{return Files.createTempFile(directory,"xcwh-public-upload-", ".bin");}
    private static void remove(Path path){if(path!=null)try{Files.deleteIfExists(path);}catch(IOException e){LoggerFactory.getLogger(PublicUploadService.class).warn("Public upload temporary cleanup failed");}}
    private void audit(AdminAccess.Actor actor, Uploaded upload, int status) {
        db.update("INSERT INTO sys_oper_log(title,business_type,oper_name,oper_param,status,oper_time) VALUES(?,2,?,?,?,CURRENT_TIMESTAMP)",
            "UPLOAD_PUBLIC_OSS", actor.username(), upload.id() + " " + upload.sha256() + " " + upload.url(), status);
    }

    private Media inspect(Path file) throws IOException {
        byte[] header;try(var input=Files.newInputStream(file)){header=input.readNBytes(16);}
        String mime,extension,format;
        if(header.length>=8&&Arrays.equals(Arrays.copyOf(header,8),new byte[]{(byte)137,80,78,71,13,10,26,10})){mime="image/png";extension="png";format="PNG";}
        else if(header.length>=3&&header[0]==(byte)255&&header[1]==(byte)216&&header[2]==(byte)255){mime="image/jpeg";extension="jpg";format="JPEG";}
        else if(header.length>=12&&ascii(header,0,4).equals("RIFF")&&ascii(header,8,4).equals("WEBP")){mime="image/webp";extension="webp";format="WebP";}
        else if(header.length>=12&&ascii(header,4,4).equals("ftyp")&&!ascii(header,8,4).equals("qt  ")){
            if(!videoEnabled)throw failure(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"PUBLIC_VIDEO_DISABLED");
            var video=videos.inspect(file);return new Media("video/mp4","mp4",video.width(),video.height());
        }else throw failure(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"PUBLIC_UPLOAD_FORMAT_UNSUPPORTED");
        try(var input=ImageIO.createImageInputStream(file.toFile())){
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw failure(HttpStatus.BAD_REQUEST,"PUBLIC_UPLOAD_CORRUPT");
            var reader=readers.next();
            try {
                if(!reader.getFormatName().equalsIgnoreCase(format))throw failure(HttpStatus.BAD_REQUEST,"PUBLIC_UPLOAD_CORRUPT");
                reader.setInput(input);int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1||height<1||width>8192||height>8192||(long)width*height>24000000)throw failure(HttpStatus.BAD_REQUEST,"PUBLIC_UPLOAD_DIMENSIONS_INVALID");
                reader.read(0);return new Media(mime,extension,width,height);
            }finally{reader.dispose();}
        }catch(IOException|IllegalArgumentException e){throw failure(HttpStatus.BAD_REQUEST,"PUBLIC_UPLOAD_CORRUPT");}
    }
    private static String ascii(byte[] bytes,int start,int length){return new String(bytes,start,length,java.nio.charset.StandardCharsets.US_ASCII);}
    private static RegistrationService.Failure failure(HttpStatus status,String code){return ContentService.failure(status,code);}
}
