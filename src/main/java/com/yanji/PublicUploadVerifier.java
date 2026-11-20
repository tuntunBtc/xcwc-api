package com.yanji;

import java.io.IOException;
import java.nio.file.Path;

public interface PublicUploadVerifier {
    void verify(String url, byte[] expected, String mime) throws IOException;
    void verifyFile(String url, Path expected, String mime) throws IOException;
}
