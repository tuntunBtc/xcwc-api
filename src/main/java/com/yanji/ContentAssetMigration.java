package com.yanji;

import java.io.IOException;
import java.nio.file.Path;
import java.security.*;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Explicit one-time startup gate. Retains local files and never rewrites database IDs. */
@Component
@ConditionalOnProperty(name="management-content.migrate-local-to-oss", havingValue="true")
public class ContentAssetMigration implements ApplicationRunner {
    private final ContentAssetStore target;
    private final LocalContentAssetStore source;
    private final JdbcTemplate db;

    public ContentAssetMigration(ContentAssetStore target, JdbcTemplate db, Environment env) {
        if (!(target instanceof OssContentAssetStore)) throw new IllegalArgumentException("Migration requires OSS destination");
        this.target=target;this.db=db;
        source=new LocalContentAssetStore(Path.of(env.getRequiredProperty("management-content.directory")));
    }

    @Override public void run(ApplicationArguments args) throws IOException {
        var rows=db.queryForList("SELECT id,mime,size_bytes,sha256 FROM content_asset ORDER BY id");
        for(var row:rows) {
            String id=(String)row.get("id"), mime=(String)row.get("mime");
            byte[] data=source.read(id), original=source.read("originals/"+id);
            if(data.length!=((Number)row.get("size_bytes")).longValue() || !sha256(data).equals(row.get("sha256")) || !Arrays.equals(data,original))
                throw new IOException("Local content integrity check failed: "+id);
            copyVerified(target,"originals/"+id,original,mime);
            copyVerified(target,id,data,mime);
            if(mime.equals("audio/mp4"))continue;
            byte[] thumbnail=source.find("thumbnails/"+id+".png").orElseGet(()->null);
            if(thumbnail==null)thumbnail=ContentService.createThumbnail(data);
            copyVerified(target,"thumbnails/"+id+".png",thumbnail,"image/png");
        }
        org.slf4j.LoggerFactory.getLogger(getClass()).info("OSS content migration verified: {} assets, local originals retained",rows.size());
    }

    static void copyVerified(ContentAssetStore target,String key,byte[] bytes,String mime) throws IOException {
        var existing=target.find(key);
        if(existing.isEmpty())target.put(key,bytes,mime);
        byte[] remote=existing.orElseGet(()->null);
        if(remote==null)remote=target.read(key);
        if(!MessageDigest.isEqual(bytes,remote))throw new IOException("OSS content verification failed: "+key);
    }

    private static String sha256(byte[] data) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
