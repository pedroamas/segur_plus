package com.segurplus.documents;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** MinIO. Las credenciales llegan del entorno (MINIO_ROOT_USER / MINIO_ROOT_PASSWORD), nunca versionadas. */
@ConfigurationProperties("segurplus.storage")
record StorageProperties(String endpoint, String accessKey, String secretKey, String bucket) {
}
