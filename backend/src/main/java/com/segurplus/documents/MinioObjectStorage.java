package com.segurplus.documents;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** MinIO con bucket privado (se crea sin política pública; no hay acceso anónimo). Falla al arrancar si MinIO no responde. */
@Component
@Order(0)
class MinioObjectStorage implements ObjectStorage, ApplicationRunner {

    private final MinioClient client;
    private final String bucket;

    MinioObjectStorage(StorageProperties props) {
        this.client = MinioClient.builder().endpoint(props.endpoint()).credentials(props.accessKey(), props.secretKey()).build();
        this.bucket = props.bucket();
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        try (InputStream in = new ByteArrayInputStream(content)) {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(in, content.length, -1)
                    .contentType(contentType).build());
        } catch (Exception e) {
            throw new StorageException("Could not store object", e);
        }
    }

    @Override
    public InputStream get(String key) {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            throw new StorageException("Could not read object", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            throw new StorageException("Could not delete object", e);
        }
    }
}
