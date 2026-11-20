package com.yanji;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Restricted local MP4 probe. No URLs, external data references, transcoding or shell invocation. */
@Component
public final class PublicUploadVideoProbe {
    private final String command;private final ObjectMapper json;
    public PublicUploadVideoProbe(@Value("${management-upload.ffprobe-command:ffprobe}") String command,ObjectMapper json){this.command=command;this.json=json;}
    record Video(int width,int height) {}
    Video inspect(Path path) throws IOException {
        validate(run(path,false),false);
        return validate(run(path,true),true);
    }
    private JsonNode run(Path path,boolean decode) throws IOException {
        Process process;
        try {
            var args=new java.util.ArrayList<>(List.of(command,"-v","error","-max_alloc","33554432","-protocol_whitelist","file",
                "-f","mov","-enable_drefs","0","-threads","1"));
            if(decode)args.addAll(List.of("-read_intervals","%+1","-count_frames"));
            args.addAll(List.of("-show_entries","format=format_name,duration:stream=codec_type,codec_name,width,height,pix_fmt,avg_frame_rate,nb_read_frames,channels,sample_rate",
                "-of","json",path.toAbsolutePath().toString()));
            process=new ProcessBuilder(args).start();
        }catch(IOException e){throw fail(HttpStatus.SERVICE_UNAVAILABLE,"PUBLIC_VIDEO_VALIDATOR_UNAVAILABLE");}
        var stdout=new FutureTask<byte[]>(()->bounded(process.getInputStream(),32768));
        var stderr=new FutureTask<byte[]>(()->bounded(process.getErrorStream(),4096));
        Thread out=new Thread(stdout,"public-video-probe-out"),err=new Thread(stderr,"public-video-probe-err");out.setDaemon(true);err.setDaemon(true);out.start();err.start();
        try {
            if(!process.waitFor(decode?15:5,TimeUnit.SECONDS))throw fail(HttpStatus.BAD_REQUEST,"PUBLIC_VIDEO_INVALID");
            byte[] output=stdout.get(1,TimeUnit.SECONDS),errors=stderr.get(1,TimeUnit.SECONDS);
            if(process.exitValue()!=0||errors.length>0)throw fail(HttpStatus.BAD_REQUEST,"PUBLIC_VIDEO_INVALID");
            return json.readTree(output);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw fail(HttpStatus.SERVICE_UNAVAILABLE,"PUBLIC_VIDEO_VALIDATION_INTERRUPTED");}
        catch(ExecutionException|TimeoutException e){throw fail(HttpStatus.BAD_REQUEST,"PUBLIC_VIDEO_INVALID");}
        finally {
            process.destroyForcibly();process.getInputStream().close();process.getErrorStream().close();process.getOutputStream().close();
            stdout.cancel(true);stderr.cancel(true);
        }
    }
    private static byte[] bounded(InputStream input,int limit) throws IOException {
        byte[] bytes=input.readNBytes(limit+1);if(bytes.length>limit)throw new IOException("Probe output limit");return bytes;
    }
    static Video validate(JsonNode root) {return validate(root,true);}
    private static Video validate(JsonNode root,boolean requireFrames) {
        if(root==null||!root.path("format").path("format_name").asText().contains("mp4"))throw fail(HttpStatus.BAD_REQUEST,"PUBLIC_VIDEO_INVALID");
        double duration=root.path("format").path("duration").asDouble(-1);
        if(!Double.isFinite(duration)||duration<=0||duration>180)throw fail(HttpStatus.BAD_REQUEST,"PUBLIC_VIDEO_DURATION_INVALID");
        JsonNode video=null;int audio=0;
        var streams=root.path("streams");if(!streams.isArray()||streams.size()<1||streams.size()>2)throw fail(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"PUBLIC_VIDEO_TRACKS_UNSUPPORTED");
        for(var stream:streams){
            if(stream.path("codec_type").asText().equals("video")) {
                String codec=stream.path("codec_name").asText(),pixels=stream.path("pix_fmt").asText();
                boolean supported=codec.equals("h264")&&pixels.equals("yuv420p")
                    ||codec.equals("hevc")&&(pixels.equals("yuv420p")||pixels.equals("yuv420p10le"));
                if(video!=null||!supported)throw fail(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"PUBLIC_VIDEO_CODEC_UNSUPPORTED");
                video=stream;
            }else if(stream.path("codec_type").asText().equals("audio")) {
                if(++audio>1||!stream.path("codec_name").asText().equals("aac")||stream.path("channels").asInt()>2||stream.path("sample_rate").asInt()>48000)
                    throw fail(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"PUBLIC_VIDEO_CODEC_UNSUPPORTED");
            }else throw fail(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"PUBLIC_VIDEO_TRACKS_UNSUPPORTED");
        }
        if(video==null||(requireFrames&&video.path("nb_read_frames").asInt()<=0))throw fail(HttpStatus.BAD_REQUEST,"PUBLIC_VIDEO_INVALID");
        int width=video.path("width").asInt(),height=video.path("height").asInt();
        if(width<1||height<1||Math.max(width,height)>1920||Math.min(width,height)>1080)throw fail(HttpStatus.BAD_REQUEST,"PUBLIC_VIDEO_DIMENSIONS_INVALID");
        String[] rate=video.path("avg_frame_rate").asText().split("/");double fps;
        try{fps=rate.length==2?Double.parseDouble(rate[0])/Double.parseDouble(rate[1]):Double.parseDouble(rate[0]);}catch(NumberFormatException e){fps=Double.NaN;}
        if(!Double.isFinite(fps)||fps<=0||fps>60)throw fail(HttpStatus.BAD_REQUEST,"PUBLIC_VIDEO_FRAME_RATE_INVALID");
        return new Video(width,height);
    }
    private static RegistrationService.Failure fail(HttpStatus status,String code){return ContentService.failure(status,code);}
}
