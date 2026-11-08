package com.segurplus.ai;

import com.segurplus.documents.Kb;
import java.util.List;
import java.util.function.Consumer;

/**
 * Puerto hacia el motor de IA (ADR 0003 §1). Hoy lo implementa Dify; cambiarlo (p. ej. Spring AI + pgvector) no toca el
 * resto. Nada de lo que devuelve es de fiar: los fragmentos se re-autorizan y el texto generado se trata como dato.
 */
public interface AiGateway {

    /** {@code false} si falta configuración (URL o claves): el chat responde 503 y el indexador no corre. */
    boolean configured();

    /** Llamada de planificación (sin conocimiento): devuelve el texto del modelo, que el backend parsea y valida. */
    Completion plan(String prompt, String difyUser);

    List<Fragment> retrieve(Kb kb, String query, RetrievalFilter filter, int topK);

    /** Generación en streaming; bloquea hasta el final. Lanza {@link AiException} (con {@code started()} según se emitiera texto). */
    Usage generate(String prompt, String difyUser, Consumer<String> onText);

    IndexedRef indexDocument(Kb kb, String name, String text, DocMetadata metadata);

    void updateMetadata(Kb kb, String difyDocumentId, DocMetadata metadata);

    void deleteDocument(Kb kb, String difyDocumentId);

    IndexingState indexingStatus(Kb kb, String difyDocumentId);

    record Usage(int tokensIn, int tokensOut) {
        public static final Usage NONE = new Usage(0, 0);

        public Usage plus(Usage o) {
            return new Usage(tokensIn + o.tokensIn, tokensOut + o.tokensOut);
        }
    }

    record Completion(String text, Usage usage) {
    }

    /** Fragmento propuesto por Dify. {@code metadataDocId} es el metadato {@code doc_id} que declara su documento (puede faltar). */
    record Fragment(String difyDocumentId, String metadataDocId, String segmentId, String content, double score) {
    }

    /** Metadatos de un documento en Dify (todos de tipo texto; {@code allowedRoles} va como {@code ,A,B,}). */
    record DocMetadata(String docId, String kb, String classification, String allowedRoles, String ownerId) {
    }

    record IndexedRef(String datasetId, String documentId) {
    }

    enum IndexingState { WAITING, INDEXING, COMPLETED, ERROR, UNKNOWN }

    /** Condición de metadatos ({@code contains}, {@code is}…) combinada con {@code and}/{@code or}. */
    record RetrievalFilter(String logicalOperator, List<Condition> conditions) {

        public static final RetrievalFilter NONE = new RetrievalFilter("and", List.of());

        public boolean isEmpty() {
            return conditions.isEmpty();
        }

        public record Condition(String name, String operator, String value) {
        }
    }
}
