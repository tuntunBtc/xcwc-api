package com.yanji;

import java.io.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentUploadStorageFailureTest {
    @Test void thumbnailWriteFailureCannotCreateSuccessfulAssetMetadata() throws Exception {
        var db=mock(JdbcTemplate.class);
        var storage=mock(ContentAssetStore.class);
        var keys=mock(RegistrationService.class);
        String id="b".repeat(32);when(keys.randomKey(16)).thenReturn(id);
        doThrow(new IOException("fixture storage outage")).when(storage).put(eq("thumbnails/"+id+".png"),any(),eq("image/png"));
        AdminAccess access=permission->new AdminAccess.Actor(1,"fixture");
        var service=new ContentService(db,new com.fasterxml.jackson.databind.ObjectMapper(),access,keys,storage);
        var image=new java.awt.image.BufferedImage(320,160,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var output=new ByteArrayOutputStream();ImageIO.write(image,"png",output);
        var file=new MockMultipartFile("file","fixture.png","image/png",output.toByteArray());
        assertThatThrownBy(()->service.upload(file,"fixture license")).isInstanceOf(IOException.class);
        verifyNoInteractions(db);
        verify(storage).put(eq("originals/"+id),eq(output.toByteArray()),eq("image/png"));
        verify(storage).put(eq(id),eq(output.toByteArray()),eq("image/png"));
    }
}
