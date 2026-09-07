package ages.vstable.backend.storage;

import ages.vstable.backend.config.S3StorageProperties;
import ages.vstable.backend.exception.ArmazenamentoDocumentoException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;

@Component
@RequiredArgsConstructor
public class S3DocumentStorage implements DocumentStorage {

    private final S3Client s3Client;
    private final S3StorageProperties properties;

    @Override
    public StoredDocument store(
            String objectKey,
            InputStream content,
            long contentLength,
            String contentType
    ) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(objectKey)
                .contentLength(contentLength)
                .contentType(contentType)
                .build();

        try {
            s3Client.putObject(
                    request,
                    RequestBody.fromInputStream(content, contentLength)
            );

            return new StoredDocument(
                    objectKey,
                    "s3://" + properties.bucket() + "/" + objectKey
            );
        } catch (SdkException exception) {
            throw new ArmazenamentoDocumentoException(
                    "Não foi possível salvar o documento no armazenamento",
                    exception
            );
        }
    }

    @Override
    public void delete(String objectKey) {
        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(properties.bucket())
                .key(objectKey)
                .build();

        try {
            s3Client.deleteObject(request);
        } catch (SdkException exception) {
            throw new ArmazenamentoDocumentoException(
                    "Não foi possível remover o documento do armazenamento",
                    exception
            );
        }
    }
}
