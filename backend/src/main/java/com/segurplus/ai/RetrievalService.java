package com.segurplus.ai;

import com.segurplus.ai.AiGateway.Fragment;
import com.segurplus.ai.AiGateway.RetrievalFilter;
import com.segurplus.audit.AuditAction;
import com.segurplus.audit.AuditEvent;
import com.segurplus.audit.AuditPublisher;
import com.segurplus.auth.Subject;
import com.segurplus.documents.DocumentCitationService;
import com.segurplus.documents.DocumentCitationService.Authorization;
import com.segurplus.documents.DocumentCitationService.Citable;
import com.segurplus.documents.Kb;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Recuperación controlada (ADR 0003 §4). Dos capas, con roles distintos:
 * <ol>
 *   <li><b>Filtro de metadatos</b> enviado a Dify, derivado del rol/identidad del JWT: solo una <i>optimización</i> de recall.</li>
 *   <li><b>Re-autorización</b> de cada fragmento por documento con {@code AccessPolicyService} <i>antes</i> de que nada llegue al
 *       LLM: el <i>control</i>. Fragmento sin mapeo, borrado o no autorizado ⇒ descartado (fallo cerrado, auditado como
 *       {@code FRAGMENT_DROPPED} solo con metadatos).</li>
 * </ol>
 * Con streaming no se puede retirar lo ya emitido, por eso la autorización va antes de generar.
 */
@Service
class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    /** Fragmento autorizado y listo para el contexto. */
    record Chunk(Citable document, String segmentId, String content, double score) {
    }

    record Outcome(List<Chunk> chunks, int retrieved, int dropped, int failedKbs) {

        /** Documentos del contexto, en orden de mejor fragmento. */
        List<Citable> documents() {
            Map<java.util.UUID, Citable> docs = new LinkedHashMap<>();
            chunks.forEach(c -> docs.putIfAbsent(c.document().id(), c.document()));
            return List.copyOf(docs.values());
        }
    }

    private final AiGateway gateway;
    private final DocumentCitationService citations;
    private final AuditPublisher audit;
    private final AiProperties props;

    RetrievalService(AiGateway gateway, DocumentCitationService citations, AuditPublisher audit, AiProperties props) {
        this.gateway = gateway;
        this.citations = citations;
        this.audit = audit;
        this.props = props;
    }

    /** Filtro de metadatos según el perfil del rol (permissions-matrix «Asistente único y fuentes»). */
    RetrievalFilter filterFor(Subject subject, AiProperties.Profile profile) {
        if (profile.unfiltered()) {
            return RetrievalFilter.NONE;
        }
        List<RetrievalFilter.Condition> conditions = new ArrayList<>();
        conditions.add(new RetrievalFilter.Condition("allowed_roles", "contains", "," + subject.role().name() + ","));
        if (profile.ownerFilter()) {
            conditions.add(new RetrievalFilter.Condition("owner_id", "is", subject.userId().toString()));
        }
        return new RetrievalFilter(profile.ownerFilter() ? "or" : "and", conditions);
    }

    Outcome retrieve(Subject subject, AiProperties.Profile profile, String query) {
        RetrievalFilter filter = filterFor(subject, profile);
        List<Kb> kbs = profile.kbs().stream().map(Kb::valueOf).toList();
        int topK = props.retrieval().topK();
        List<CompletableFuture<List<Fragment>>> calls = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Kb kb : kbs) {
                calls.add(CompletableFuture.supplyAsync(() -> gateway.retrieve(kb, query, filter, topK), executor));
            }
            List<Fragment> all = new ArrayList<>();
            int failed = 0;
            AiException lastFailure = null;
            for (var call : calls) {
                try {
                    all.addAll(call.join());
                } catch (RuntimeException e) {
                    failed++;
                    lastFailure = e.getCause() instanceof AiException ai ? ai : null;
                    log.warn("Retrieval from one KB failed ({})", e.getCause() == null ? e.getClass().getSimpleName() : e.getCause().getClass().getSimpleName());
                }
            }
            if (failed == kbs.size() && !kbs.isEmpty()) {
                throw lastFailure != null ? lastFailure : new AiException(AiException.Kind.UNAVAILABLE, "Retrieval failed");
            }
            return authorize(subject, all, failed);
        }
    }

    /** Re-autoriza fragmentos ya recuperados (accesible a tests con un corpus completo simulado). */
    Outcome authorize(Subject subject, List<Fragment> fragments, int failedKbs) {
        Map<String, String> difyIds = new LinkedHashMap<>();
        for (Fragment f : fragments) {
            if (f.difyDocumentId() != null) {
                difyIds.putIfAbsent(f.difyDocumentId(), f.metadataDocId());
            }
        }
        Authorization auth = citations.authorizeFragments(subject, difyIds);
        Set<String> reported = new HashSet<>();
        for (var d : auth.dropped()) {
            if (reported.add(d.difyDocumentId())) {
                audit.publish(new AuditEvent(AuditAction.FRAGMENT_DROPPED, subject.userId(), "DOCUMENT",
                        d.documentId() == null ? null : d.documentId().toString(), false, d.reason(), AuditEvent.Channel.RAG, Map.of()));
            }
        }
        int fragmentsWithoutDocument = (int) fragments.stream().filter(f -> f.difyDocumentId() == null).count();
        List<Chunk> chunks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int dropped = fragmentsWithoutDocument;
        for (Fragment f : fragments) {
            Citable doc = f.difyDocumentId() == null ? null : auth.allowed().get(f.difyDocumentId());
            if (doc == null) {
                if (f.difyDocumentId() != null) {
                    dropped++;
                }
                continue;
            }
            if (f.content() == null || f.content().isBlank() || !seen.add(f.segmentId() == null ? f.content() : f.segmentId())) {
                continue;
            }
            chunks.add(new Chunk(doc, f.segmentId(), f.content(), f.score()));
        }
        chunks.sort(Comparator.comparingDouble(Chunk::score).reversed());
        List<Chunk> kept = chunks.size() > props.retrieval().maxContextChunks()
                ? List.copyOf(chunks.subList(0, props.retrieval().maxContextChunks())) : List.copyOf(chunks);
        return new Outcome(kept, fragments.size(), dropped, failedKbs);
    }
}
