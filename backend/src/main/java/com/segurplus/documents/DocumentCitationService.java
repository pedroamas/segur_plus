package com.segurplus.documents;

import com.segurplus.auth.AccessPolicyService;
import com.segurplus.auth.Action;
import com.segurplus.auth.ResourceAttributes;
import com.segurplus.auth.Subject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-autorización de fragmentos y citas (ADR 0003 §4 y §10). Dify no tiene ACL por usuario: lo que devuelve es una
 * <i>propuesta</i>. Aquí cada ID de documento de Dify se mapea a {@code documents.id} y se decide READ con
 * {@link AccessPolicyService} (una consulta por lote). Sin mapeo, borrado o no autorizado ⇒ descartado (fallo cerrado).
 */
@Service
public class DocumentCitationService {

    /** Documento citable (solo lo imprescindible para citar; sin contenido ni propietario). */
    public record Citable(UUID id, String code, String title, Kb kb) {
    }

    /** Fragmento descartado; {@code documentId} es null si no mapea a ningún documento. */
    public record Dropped(String difyDocumentId, UUID documentId, String reason) {
    }

    public record Authorization(Map<String, Citable> allowed, List<Dropped> dropped) {
    }

    private final DocumentRepository documents;
    private final AccessPolicyService policy;

    DocumentCitationService(DocumentRepository documents, AccessPolicyService policy) {
        this.documents = documents;
        this.policy = policy;
    }

    /**
     * @param difyToMetadataDocId para cada documento de Dify, el {@code doc_id} que declara su metadato (o null). Si no coincide con
     *                            el {@code documents.id} mapeado, el fragmento se descarta (índice incoherente).
     */
    @Transactional(readOnly = true)
    public Authorization authorizeFragments(Subject subject, Map<String, String> difyToMetadataDocId) {
        Map<String, Citable> allowed = new LinkedHashMap<>();
        List<Dropped> dropped = new ArrayList<>();
        if (difyToMetadataDocId.isEmpty()) {
            return new Authorization(allowed, dropped);
        }
        Map<String, Document> byDify = documents.findByDifyDocumentIdIn(difyToMetadataDocId.keySet()).stream()
                .collect(Collectors.toMap(Document::getDifyDocumentId, d -> d, (a, b) -> a));
        Set<UUID> authorized = policy.authorizeBatch(subject, Action.READ,
                byDify.values().stream().map(Document::getId).toList(), Document.class);
        for (var entry : difyToMetadataDocId.entrySet()) {
            Document d = byDify.get(entry.getKey());
            if (d == null) {
                dropped.add(new Dropped(entry.getKey(), null, "UNMAPPED"));
            } else if (!authorized.contains(d.getId())
                    || !policy.decide(subject, Action.READ, new ResourceAttributes(d.getAllowedRoles(), d.getOwnerId(), d.getDeletedAt() != null)).allowed()) {
                dropped.add(new Dropped(entry.getKey(), d.getId(), "NOT_AUTHORIZED"));
            } else if (entry.getValue() != null && !entry.getValue().equals(d.getId().toString())) {
                dropped.add(new Dropped(entry.getKey(), d.getId(), "METADATA_MISMATCH"));
            } else {
                allowed.put(entry.getKey(), new Citable(d.getId(), d.getCode(), d.getTitle(), d.getKb()));
            }
        }
        return new Authorization(allowed, dropped);
    }

    /** Documentos de {@code ids} que el usuario puede leer AHORA (para releer el historial: las citas se re-autorizan). */
    @Transactional(readOnly = true)
    public Map<UUID, Citable> readable(Subject subject, Collection<UUID> ids) {
        Set<UUID> wanted = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        Set<UUID> authorized = policy.authorizeBatch(subject, Action.READ, wanted, Document.class);
        Map<UUID, Citable> out = new HashMap<>();
        documents.findAllById(authorized).forEach(d -> out.put(d.getId(), new Citable(d.getId(), d.getCode(), d.getTitle(), d.getKb())));
        return out;
    }
}
