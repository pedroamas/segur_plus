package com.segurplus.support;

import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Contenedores compartidos por toda la ejecución de tests (se arrancan una vez; Ryuk los limpia al terminar). */
public final class Containers {

    /** minio/minio ya no está en Docker Hub; imagen de Chainguard fijada por digest (mismo valor que deploy/docker-compose.yml). */
    static final String MINIO_IMAGE = "cgr.dev/chainguard/minio@sha256:cee9a8e35885e93f14b3cb32960e5017aff74e2adfb52ba87c65003b0163886b";

    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("segurplus_test").withUsername("test").withPassword("test-" + java.util.UUID.randomUUID());

    public static final MinIOContainer MINIO = new MinIOContainer(DockerImageName.parse(MINIO_IMAGE).asCompatibleSubstituteFor("minio/minio"))
            .withUserName("testminio").withPassword("test-" + java.util.UUID.randomUUID());

    static {
        POSTGRES.start();
        MINIO.start();
    }

    private Containers() {
    }
}
