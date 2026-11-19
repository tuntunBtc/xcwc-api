package com.yanji;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;

/** Only the opt-in animation workflow needs ffprobe. Decode the sole AAC track before publication. */
final class AnimationAudio {
    private AnimationAudio() {}

    static long durationMs(byte[] data,ObjectMapper json) throws IOException {
        Path input=Files.createTempFile("xcwc-animation-", ".m4a");
        Process probe=null;
        try {
            Files.write(input,data);
            var command=new ProcessBuilder("ffprobe","-v","error","-max_alloc","33554432","-protocol_whitelist","file",
                "-f","mov","-enable_drefs","0","-threads","1","-count_frames","-show_entries",
                "stream=codec_name,codec_type,duration,nb_read_frames","-of","json",input.toString());
            try {probe=command.start();}
            catch(IOException e){throw ContentService.failure(HttpStatus.SERVICE_UNAVAILABLE,"AUDIO_VALIDATOR_UNAVAILABLE");}
            if(!probe.waitFor(10,TimeUnit.SECONDS))throw ContentRules.fail("INVALID_ANIMATION_AUDIO");
            if(probe.exitValue()!=0||probe.getErrorStream().read()!=-1)throw ContentRules.fail("INVALID_ANIMATION_AUDIO");
            var streams=json.readTree(probe.getInputStream()).path("streams");
            if(streams.size()!=1)throw ContentRules.fail("INVALID_ANIMATION_AUDIO");
            var track=streams.get(0);
            long ms=Math.round(track.path("duration").asDouble()*1000);
            if(!track.path("codec_type").asText().equals("audio")||!track.path("codec_name").asText().equals("aac")
                    ||track.path("nb_read_frames").asInt()<=0||ms<1||ms>120000)
                throw ContentRules.fail("INVALID_ANIMATION_AUDIO");
            return ms;
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            throw ContentService.failure(HttpStatus.SERVICE_UNAVAILABLE,"AUDIO_VALIDATION_INTERRUPTED");
        }finally{
            if(probe!=null)probe.destroyForcibly();
            Files.deleteIfExists(input);
        }
    }
}
