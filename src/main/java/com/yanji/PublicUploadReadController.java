package com.yanji;

import jakarta.servlet.http.*;
import java.io.*;
import java.nio.file.Files;
import org.springframework.web.bind.annotation.*;

@RestController
public class PublicUploadReadController {
    private final PublicUploadService uploads;
    public PublicUploadReadController(PublicUploadService uploads){this.uploads=uploads;}
    @RequestMapping(value="/bird/v1/oss/uploads/{filename}",method={RequestMethod.GET,RequestMethod.HEAD})
    public void download(@PathVariable String filename,HttpServletRequest request,HttpServletResponse response) throws IOException {
        var metadata=uploads.metadata(filename);String etag="\""+metadata.sha256()+"\"";
        String cache=metadata.ready()?"public, max-age=31536000, immutable":"no-store";
        response.setHeader("Cache-Control","no-store");
        response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("Content-Disposition","inline");
        response.setHeader("ETag",etag);response.setHeader("Accept-Ranges","bytes");
        String none=request.getHeader("If-None-Match");
        if(metadata.ready()&&none!=null&&java.util.Arrays.stream(none.split(",")).map(String::strip).anyMatch(x->x.equals("*")||x.equals(etag)||x.equals("W/"+etag))){response.setHeader("Cache-Control",cache);response.setStatus(304);return;}
        String range=request.getHeader("Range"),ifRange=request.getHeader("If-Range");
        if(ifRange!=null&&!ifRange.equals(etag))range=null;
        long[] bounds;
        try{bounds=range==null?new long[]{0,metadata.size()-1}:range(range,metadata.size());}
        catch(IllegalArgumentException e){response.setStatus(416);response.setHeader("Content-Range","bytes */"+metadata.size());response.setContentLengthLong(0);return;}
        // Verify the complete OSS object before committing any bytes, then serve only the requested interval.
        try(var file=uploads.read(metadata)) {
            long length=bounds[1]-bounds[0]+1;response.setContentType(metadata.mime());
            response.setHeader("Cache-Control",cache);response.setStatus(range==null?200:206);response.setContentLengthLong(length);
            if(range!=null)response.setHeader("Content-Range","bytes "+bounds[0]+"-"+bounds[1]+"/"+metadata.size());
            if(request.getMethod().equals("HEAD"))return;
            try(var input=Files.newInputStream(file.path())) {
                input.skipNBytes(bounds[0]);byte[] buffer=new byte[65536];long left=length;
                while(left>0){int n=input.read(buffer,0,(int)Math.min(buffer.length,left));if(n<0)throw new EOFException();response.getOutputStream().write(buffer,0,n);left-=n;}
            }
        }
    }
    static long[] range(String value,long size) {
        if(!value.matches("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)"))throw new IllegalArgumentException();
        String[] parts=value.substring(6).split("-",-1);
        try {
            long first,last;
            if(parts[0].isEmpty()){long suffix=Long.parseLong(parts[1]);if(suffix<1)throw new IllegalArgumentException();first=Math.max(0,size-suffix);last=size-1;}
            else {first=Long.parseLong(parts[0]);last=parts[1].isEmpty()?size-1:Math.min(size-1,Long.parseLong(parts[1]));}
            if(first<0||first>=size||last<first)throw new IllegalArgumentException();return new long[]{first,last};
        }catch(NumberFormatException e){throw new IllegalArgumentException(e);}
    }
}
