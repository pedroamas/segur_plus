package com.segurplus.documents;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** No público: el resto de la aplicación usa {@link DocumentService}. */
interface DocumentRepository extends JpaRepository<Document, UUID>, JpaSpecificationExecutor<Document> {

    boolean existsByCode(String code);
}
