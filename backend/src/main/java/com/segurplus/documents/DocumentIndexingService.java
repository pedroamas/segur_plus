package com.segurplus.documents;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Cara pública de {@code documents} para el indexador (paquete {@code ai}): lee metadatos, extrae el texto del original y
 * mueve {@code index_status} (PENDING → INDEXING → INDEXED | FAILED). PostgreSQL es la fuente de verdad; Dify es una copia derivada.
 * <p>Las transiciones usan {@code now()} de la BD (tiempo real) en {@code updated_at}: sirve para reintentos y atascos, no es
 * una fecha de negocio.
 */
@Service
public class DocumentIndexingService {

    /** Metadatos del documento tal como deben viajar a Dify. */
    public record IndexableDocument(UUID id, String code, String title, FileType fileType, Kb kb, Classification classification,
            UUID ownerId, List<String> allowedRoles, String difyDatasetId, String difyDocumentId, String indexStatus,
            boolean deleted) {
    }

    private final DocumentRepository documents;
    private final ObjectStorage storage;
    private final JdbcClient jdbc;

    DocumentIndexingService(DocumentRepository documents, ObjectStorage storage, JdbcClient jdbc) {
        this.documents = documents;
        this.storage = storage;
        this.jdbc = jdbc;
    }

    public Optional<IndexableDocument> find(UUID id) {
        return documents.findById(id).map(DocumentIndexingService::toIndexable);
    }

    /** Transición atómica PENDING|FAILED → INDEXING. {@code false} si otro hilo ya lo tomó o está borrado. */
    public boolean claim(UUID id) {
        return jdbc.sql("UPDATE documents SET index_status = 'INDEXING', updated_at = now() "
                + "WHERE id = :id AND deleted_at IS NULL AND index_status IN ('PENDING','FAILED')")
                .param("id", id).update() == 1;
    }

    /** Texto plano del original (de MinIO) para enviarlo a Dify. */
    public String extractText(UUID id) throws IOException {
        Document doc = documents.findById(id).orElseThrow(() -> new IOException("Document not found"));
        try (InputStream in = storage.get(doc.getStorageKey())) {
            return DocumentTextExtractor.extract(doc.getFileType(), in.readAllBytes(), doc.getCode(), doc.getTitle());
        }
    }

    public void markIndexing(UUID id, String difyDatasetId, String difyDocumentId) {
        jdbc.sql("UPDATE documents SET index_status = 'INDEXING', dify_dataset_id = :ds, dify_document_id = :doc, updated_at = now() "
                + "WHERE id = :id").param("ds", difyDatasetId).param("doc", difyDocumentId).param("id", id).update();
    }

    public void markIndexed(UUID id) {
        jdbc.sql("UPDATE documents SET index_status = 'INDEXED', indexed_at = now(), updated_at = now() "
                + "WHERE id = :id AND dify_document_id IS NOT NULL").param("id", id).update();
    }

    public void markFailed(UUID id) {
        jdbc.sql("UPDATE documents SET index_status = 'FAILED', updated_at = now() WHERE id = :id").param("id", id).update();
    }

    /** Reindexado o cambio de metadatos: vuelve a PENDING. */
    public void markPending(UUID id) {
        jdbc.sql("UPDATE documents SET index_status = 'PENDING', updated_at = now() WHERE id = :id").param("id", id).update();
    }

    /** Olvida la copia de Dify (tras borrarla o al recrearla); un INDEXED pasa a PENDING (el CHECK ck_documents_indexed exige copia en Dify). */
    public void clearDifyReference(UUID id) {
        jdbc.sql("UPDATE documents SET dify_dataset_id = NULL, dify_document_id = NULL, indexed_at = NULL, "
                + "index_status = CASE WHEN index_status = 'INDEXED' THEN 'PENDING' ELSE index_status END, updated_at = now() "
                + "WHERE id = :id").param("id", id).update();
    }

    /** Documentos vivos pendientes, o fallidos hace más de {@code retryAfterSeconds}. */
    public List<UUID> toIndex(long retryAfterSeconds) {
        return jdbc.sql("SELECT id FROM documents WHERE deleted_at IS NULL AND (index_status = 'PENDING' "
                + "OR (index_status = 'FAILED' AND updated_at < now() - make_interval(secs => :s))) ORDER BY code")
                .param("s", retryAfterSeconds).query(UUID.class).list();
    }

    /** Documentos en INDEXING (para consultar el estado en Dify o rescatar atascos). */
    public List<IndexableDocument> indexing() {
        return documents.findAll().stream().filter(d -> "INDEXING".equals(d.getIndexStatus()) && d.getDeletedAt() == null)
                .map(DocumentIndexingService::toIndexable).toList();
    }

    /** INDEXING sin avanzar desde hace más de {@code seconds}: el job los pasa a FAILED. */
    public int failStuck(long seconds) {
        return jdbc.sql("UPDATE documents SET index_status = 'FAILED', updated_at = now() WHERE index_status = 'INDEXING' "
                + "AND updated_at < now() - make_interval(secs => :s)").param("s", seconds).update();
    }

    /** Borrados lógicos cuya copia sigue en Dify. */
    public List<IndexableDocument> deletedStillInDify() {
        return documents.findAll().stream().filter(d -> d.getDeletedAt() != null && d.getDifyDocumentId() != null)
                .map(DocumentIndexingService::toIndexable).toList();
    }

    private static IndexableDocument toIndexable(Document d) {
        return new IndexableDocument(d.getId(), d.getCode(), d.getTitle(), d.getFileType(), d.getKb(), d.getClassification(),
                d.getOwnerId(), d.getAllowedRoles(), d.getDifyDatasetId(), d.getDifyDocumentId(), d.getIndexStatus(),
                d.getDeletedAt() != null);
    }
}
