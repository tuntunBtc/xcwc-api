package com.yanji;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;

class PublicUploadVideoProbeTest {
    final ObjectMapper json=new ObjectMapper();
    ObjectNodeFixture fixture() throws Exception{return new ObjectNodeFixture(json.readTree("{\"format\":{\"format_name\":\"mov,mp4\",\"duration\":\"1\"},\"streams\":[{\"codec_type\":\"video\",\"codec_name\":\"h264\",\"pix_fmt\":\"yuv420p\",\"width\":160,\"height\":120,\"avg_frame_rate\":\"24/1\",\"nb_read_frames\":24}]}"));}
    record ObjectNodeFixture(JsonNode root){com.fasterxml.jackson.databind.node.ObjectNode video(){return (com.fasterxml.jackson.databind.node.ObjectNode)root.path("streams").get(0);}}
    @Test void limitsRejectDurationResolutionFrameRateAndUnsupportedCodecs() throws Exception {
        var good=fixture();assertThat(PublicUploadVideoProbe.validate(good.root()).width()).isEqualTo(160);
        for(String field:new String[]{"codec_name","pix_fmt","width","height","avg_frame_rate","nb_read_frames"}){
            var bad=fixture();switch(field){case "codec_name"->bad.video().put(field,"vp9");case "pix_fmt"->bad.video().put(field,"yuv420p10le");case "width"->bad.video().put(field,3840);case "height"->bad.video().put(field,2160);case "avg_frame_rate"->bad.video().put(field,"120/1");default->bad.video().put(field,0);}
            assertThatThrownBy(()->PublicUploadVideoProbe.validate(bad.root())).isInstanceOf(RegistrationService.Failure.class);
        }
        var longVideo=fixture();((com.fasterxml.jackson.databind.node.ObjectNode)longVideo.root().path("format")).put("duration","181");assertThatThrownBy(()->PublicUploadVideoProbe.validate(longVideo.root())).isInstanceOf(RegistrationService.Failure.class);
    }
    @Test void unavailableProbeIsAnExplicitServiceFailure() throws Exception {
        Path file=Files.createTempFile("xcwh-public-probe-test-", ".mp4");try{
            assertThatThrownBy(()->new PublicUploadVideoProbe("/missing/xcwh-ffprobe",json).inspect(file)).isInstanceOfSatisfying(RegistrationService.Failure.class,e->assertThat(e.code).isEqualTo("PUBLIC_VIDEO_VALIDATOR_UNAVAILABLE"));
        }finally{Files.deleteIfExists(file);}
    }
    @Test void hevcAcceptsOnlyEightOrTenBit420WithoutWideningH264() throws Exception {
        for(String pixels:new String[]{"yuv420p","yuv420p10le"}){
            var good=fixture();good.video().put("codec_name","hevc").put("pix_fmt",pixels);
            assertThat(PublicUploadVideoProbe.validate(good.root()).width()).isEqualTo(160);
        }
        for(String codec:new String[]{"h264","hevc","vp9","av1"}){
            for(String pixels:new String[]{"yuv420p12le","yuv422p","yuv444p","yuv420p10le"}){
                if(codec.equals("hevc")&&pixels.equals("yuv420p10le"))continue;
                var bad=fixture();bad.video().put("codec_name",codec).put("pix_fmt",pixels);
                assertThatThrownBy(()->PublicUploadVideoProbe.validate(bad.root()))
                    .isInstanceOfSatisfying(RegistrationService.Failure.class,e->assertThat(e.code).isEqualTo("PUBLIC_VIDEO_CODEC_UNSUPPORTED"));
            }
        }
    }
}
