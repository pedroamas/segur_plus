package com.segurplus.ai;

import com.segurplus.ai.AiGateway.DocMetadata;
import com.segurplus.ai.AiGateway.IndexedRef;
import com.segurplus.ai.AiGateway.IndexingState;
import com.segurplus.documents.DocumentChanged;
import com.segurplus.documents.DocumentIndexingService;
import com.segurplus.documents.DocumentIndexingService.IndexableDocument;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Indexador (ADR 0004): copia derivada de {@code documents} en Dify. Escucha {@link DocumentChanged} y además un job de
 * reintento recoge PENDING/FAILED, rescata INDEXING atascados y limpia en Dify los borrados lógicos.
 * <pre>PENDING → INDEXING → INDEXED | FAILED;  FAILED → PENDING (job);  INDEXED → PENDING (reindexado)</pre>
 * La BD manda: el evento solo lleva el ID y los metadatos que viajan a Dify se leen de la fila vigente. Nada de contenido
 * en logs (solo códigos de documento y clases de excepción).
 */
@Component
class DocumentIndexer {

    private static final Logger log = LoggerFactory.getLogger(DocumentIndexer.class);

    private final AiGateway gateway;
    private final DocumentIndexingService docs;
    private final AiProperties props;
    private final ExecutorService events = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "document-indexer");
        t.setDaemon(true);
        return t;
    });
    private final ReentrantLock jobLock = new ReentrantLock();

    DocumentIndexer(AiGateway gateway, DocumentIndexingService docs, AiProperties props) {
        this.gateway = gateway;
        this.docs = docs;
        this.props = props;
    }

    @PreDestroy
    void shutdown() {
        events.shutdownNow();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onDocumentChanged(DocumentChanged event) {
        if (!active()) {
            return; // el job lo recogerá cuando haya Dify
        }
        events.submit(() -> {
            try {
                handle(event);
            } catch (RuntimeException e) {
                log.warn("Indexer event failed ({})", e.getClass().getSimpleName());
            }
        });
    }

    void handle(DocumentChanged event) {
        switch (event.kind()) {
            case CREATED -> index(event.documentId());
            case REINDEX -> {
                docs.markPending(event.documentId());
                index(event.documentId());
            }
            case METADATA_CHANGED -> refreshMetadata(event.documentId());
            case DELETED -> docs.find(event.documentId()).ifPresent(this::removeFromDify);
        }
    }

    @Scheduled(initialDelayString = "${segurplus.ai.indexer.interval-seconds:30}", fixedDelayString = "${segurplus.ai.indexer.interval-seconds:30}",
            timeUnit = TimeUnit.SECONDS)
    void scheduledRun() {
        if (active()) {
            runOnce();
        }
    }

    /** Una pasada del job de reintento (accesible a tests). Una sola a la vez. */
    void runOnce() {
        if (!jobLock.tryLock()) {
            return;
        }
        try {
            AiProperties.Indexer cfg = props.indexer();
            int stuck = docs.failStuck(cfg.stuckAfterSeconds());
            if (stuck > 0) {
                log.warn("Indexer: {} documents were stuck in INDEXING and are now FAILED", stuck);
            }
            for (IndexableDocument d : docs.indexing()) {
                pollOnce(d);
            }
            for (UUID id : docs.toIndex(cfg.retryAfterSeconds())) {
                index(id);
            }
            for (IndexableDocument d : docs.deletedStillInDify()) {
                removeFromDify(d);
            }
        } catch (RuntimeException e) {
            log.warn("Indexer job failed ({})", e.getClass().getSimpleName());
        } finally {
            jobLock.unlock();
        }
    }

    /** Indexa un documento (PENDING|FAILED → INDEXING → INDEXED|FAILED). Idempotente: si ya tiene copia en Dify, la recrea. */
    void index(UUID id) {
        IndexableDocument doc = docs.find(id).orElse(null);
        if (doc == null || doc.deleted() || !docs.claim(id)) {
            return;
        }
        try {
            if (doc.difyDocumentId() != null) { // reindexado: nada de duplicados
                gateway.deleteDocument(doc.kb(), doc.difyDocumentId());
                docs.clearDifyReference(id);
            }
            String text = docs.extractText(id);
            if (text.isBlank()) {
                throw new IllegalStateException("empty text");
            }
            IndexedRef ref = gateway.indexDocument(doc.kb(), doc.code() + " - " + doc.title(), text, metadataOf(doc));
            docs.markIndexing(id, ref.datasetId(), ref.documentId());
            awaitIndexed(doc.id(), doc.kb(), ref.documentId());
        } catch (Exception e) {
            log.warn("Indexing of document {} failed ({})", doc.code(), e.getClass().getSimpleName());
            docs.markFailed(id);
        }
    }

    private void awaitIndexed(UUID id, com.segurplus.documents.Kb kb, String difyDocumentId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(props.indexer().pollTimeoutSeconds()).toNanos();
        while (System.nanoTime() < deadline) {
            IndexingState state = gateway.indexingStatus(kb, difyDocumentId);
            if (state == IndexingState.COMPLETED) {
                docs.markIndexed(id);
                return;
            }
            if (state == IndexingState.ERROR) {
                docs.markFailed(id);
                return;
            }
            Thread.sleep(1500);
        }
        // sigue INDEXING: el job lo consultará de nuevo (o lo marcará FAILED si se atasca)
    }

    private void pollOnce(IndexableDocument d) {
        if (d.difyDocumentId() == null) {
            return;
        }
        try {
            IndexingState state = gateway.indexingStatus(d.kb(), d.difyDocumentId());
            if (state == IndexingState.COMPLETED) {
                docs.markIndexed(d.id());
            } else if (state == IndexingState.ERROR) {
                docs.markFailed(d.id());
            }
        } catch (AiException e) {
            log.warn("Indexing status of {} unavailable ({})", d.code(), e.kind());
        }
    }

    /** Cambio de roles/propietario/KB/clasificación: se reenvían los metadatos; si falla, se reindexa. Mientras tanto manda la re-autorización. */
    void refreshMetadata(UUID id) {
        IndexableDocument doc = docs.find(id).orElse(null);
        if (doc == null || doc.deleted()) {
            return;
        }
        if (doc.difyDocumentId() == null) {
            index(id);
            return;
        }
        try {
            gateway.updateMetadata(doc.kb(), doc.difyDocumentId(), metadataOf(doc));
        } catch (RuntimeException e) {
            log.warn("Metadata refresh of {} failed ({}); scheduling reindex", doc.code(), e.getClass().getSimpleName());
            docs.markPending(id);
        }
    }

    private void removeFromDify(IndexableDocument doc) {
        if (doc.difyDocumentId() == null) {
            return;
        }
        try {
            gateway.deleteDocument(doc.kb(), doc.difyDocumentId());
            docs.clearDifyReference(doc.id());
        } catch (RuntimeException e) {
            log.warn("Deleting {} from Dify failed ({}); the job will retry", doc.code(), e.getClass().getSimpleName());
        }
    }

    private boolean active() {
        return props.indexer().enabled() && gateway.configured();
    }

    /** Metadatos de Dify desde la fila vigente. {@code allowed_roles} lleva delimitadores para que {@code contains} sea exacto. */
    static DocMetadata metadataOf(IndexableDocument d) {
        return new DocMetadata(d.id().toString(), d.kb().name(), d.classification().name(),
                "," + String.join(",", d.allowedRoles()) + ",", d.ownerId() == null ? null : d.ownerId().toString());
    }
}
