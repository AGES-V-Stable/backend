package ages.vstable.backend.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

@Configuration
@EnableConfigurationProperties(S3StorageProperties.class)
public class S3ClientConfiguration {

    @Bean
    public S3Client s3Client(S3StorageProperties properties) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(properties.region()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyleAccess())
                        .build());

        configureEndpoint(builder, properties.endpoint());
        configureCredentials(builder, properties.accessKey(), properties.secretKey());

        return builder.build();
    }

    private void configureEndpoint(S3ClientBuilder builder, String endpoint) {
        if (StringUtils.hasText(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
        }
    }

    private void configureCredentials(
            S3ClientBuilder builder,
            String accessKey,
            String secretKey
    ) {
        boolean hasAccessKey = StringUtils.hasText(accessKey);
        boolean hasSecretKey = StringUtils.hasText(secretKey);

        if (hasAccessKey != hasSecretKey) {
            throw new IllegalStateException(
                    "S3_ACCESS_KEY e S3_SECRET_KEY devem ser informadas em conjunto"
            );
        }

        if (hasAccessKey) {
            AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);
            builder.credentialsProvider(StaticCredentialsProvider.create(credentials));
            return;
        }

        builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
    }
}
