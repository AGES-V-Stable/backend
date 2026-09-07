package ages.vstable.backend.storage;

import java.io.InputStream;

public interface DocumentStorage {

    StoredDocument store(
            String objectKey,
            InputStream content,
            long contentLength,
            String contentType
    );

    void delete(String objectKey);
}
