package com.segurplus.documents;

import com.segurplus.audit.AuditAction;
import com.segurplus.audit.AuditEvent;
import com.segurplus.audit.AuditPublisher;
import com.segurplus.auth.AccessPolicyService;
import com.segurplus.auth.Action;
import com.segurplus.auth.Decision;
import com.segurplus.auth.ResourceAttributes;
import com.segurplus.auth.Role;
import com.segurplus.auth.Subject;
import com.segurplus.documents.api.DocumentDetail;
import com.segurplus.documents.api.DocumentPage;
import com.segurplus.documents.api.DocumentSummary;
import com.segurplus.documents.api.ExcelPreview;
import com.segurplus.documents.api.UploadMetadata;
import com.segurplus.shared.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso de documentos. Toda decisión pasa por {@link AccessPolicyService}: los listados llevan la política en la
 * consulta (Specification) y los accesos por ID cargan SOLO si la política lo permite. Un documento inexistente y uno no
 * autorizado producen exactamente el mismo 404; el motivo real queda solo en {@code audit_logs} (ACCESS_DENIED).
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final String RESOURCE_TYPE = "DOCUMENT";
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9]{2,8}$");
    private static final int MAX_PAGE_SIZE = 100;

    private final DocumentRepository documents;
    private final ObjectStorage storage;
    private final AccessPolicyService policy;
    private final AuditPublisher audit;
    private final Clock clock;
    private final DocumentsProperties props;

    DocumentService(DocumentRepository documents, ObjectStorage storage, AccessPolicyService policy, AuditPublisher audit,
            Clock clock, DocumentsProperties props) {
        this.documents = documents;
        this.storage = storage;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public DocumentPage list(Subject subject, Kb kb, DocCategory category, FileType fileType, int page, int size) {
        Specification<Document> spec = policy.<Document>toSpecification(subject, Action.READ)
                .and(equalsIfPresent("kb", kb)).and(equalsIfPresent("category", category)).and(equalsIfPresent("fileType", fileType));
        var result = documents.findAll(spec, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), Sort.by("code")));
        return new DocumentPage(result.getContent().stream().map(DocumentService::toSummary).toList(), result.getNumber(),
                result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public DocumentDetail get(Subject subject, UUID id) {
        return toDetail(loadAuthorized(subject, id, Action.READ));
    }

    /** Visor PDF: entrega inline. Otros tipos ⇒ 415. Audita DOCUMENT_READ. */
    @Transactional(readOnly = true)
    public DocumentContent view(Subject subject, UUID id) {
        Document doc = loadAuthorized(subject, id, Action.READ);
        if (doc.getFileType() != FileType.PDF) {
            throw ApiException.unsupportedMedia("Only PDF documents can be viewed inline.");
        }
        DocumentContent content = open(doc);
        audit.publish(allowed(AuditAction.DOCUMENT_READ, subject, doc, "VIEW"));
        return content;
    }

    /** Preview Excel renderizado en el servidor. Otros tipos ⇒ 415. Audita DOCUMENT_READ. */
    @Transactional(readOnly = true)
    public ExcelPreview preview(Subject subject, UUID id) {
        Document doc = loadAuthorized(subject, id, Action.READ);
        if (doc.getFileType() != FileType.XLSX) {
            throw ApiException.unsupportedMedia("Only XLSX documents can be previewed.");
        }
        try (InputStream in = storage.get(doc.getStorageKey())) {
            ExcelPreview preview = ExcelPreviewRenderer.render(in.readAllBytes(), props.preview());
            audit.publish(allowed(AuditAction.DOCUMENT_READ, subject, doc, "PREVIEW"));
            return preview;
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException api) {
                throw api;
            }
            log.warn("Preview failed for document {} ({})", id, e.getClass().getSimpleName());
            throw ApiException.unprocessable("The workbook could not be rendered.");
        }
    }

    /** Descarga: se audita DOCUMENT_DOWNLOAD (ALLOW) antes de entregar el contenido. */
    @Transactional(readOnly = true)
    public DocumentContent download(Subject subject, UUID id) {
        Document doc = loadAuthorized(subject, id, Action.DOWNLOAD);
        DocumentContent content = open(doc);
        audit.publish(allowed(AuditAction.DOCUMENT_DOWNLOAD, subject, doc, "DOWNLOAD"));
        return content;
    }

    /** Subida: solo MANAGER (403 + ACCESS_DENIED para el resto). Valida tipo real, extensión, tamaño y metadatos. */
    public DocumentDetail upload(Subject subject, UploadMetadata meta, String originalFilename, byte[] content) {
        Decision decision = policy.decide(subject, Action.CREATE, null);
        if (!decision.allowed()) {
            audit.publish(new AuditEvent(AuditAction.ACCESS_DENIED, subject.userId(), RESOURCE_TYPE, null, false,
                    decision.reasonCode(), AuditEvent.Channel.REST, Map.of("action", Action.CREATE.name())));
            throw ApiException.forbidden();
        }
        if (content == null || content.length == 0) {
            throw ApiException.unprocessable("The file is empty.");
        }
        if (content.length > props.maxUploadBytes()) {
            throw ApiException.payloadTooLarge("The uploaded file exceeds the maximum allowed size.");
        }
        FileType type = FileTypeDetector.detectAndMatch(content, originalFilename);
        List<String> roles = validatedRoles(meta);
        if (meta.code() == null || !CODE.matcher(meta.code()).matches()) {
            throw ApiException.unprocessable("code must be 2-8 characters (A-Z, 0-9).");
        }
        if (meta.title() == null || meta.title().isBlank() || meta.title().length() > 200) {
            throw ApiException.unprocessable("title is required (max 200 characters).");
        }
        Document saved = store(subject.userId(), meta, roles, type, content);
        audit.publish(allowed(AuditAction.DOCUMENT_UPLOAD, subject, saved, "UPLOAD")
                .withDetails(Map.of("fileType", type.name(), "sizeBytes", saved.getSizeBytes())));
        return toDetail(saved);
    }

    /** Alta sin comprobación de política: SOLO para la semilla de desarrollo (mismo paquete). */
    Document createUnchecked(UUID createdBy, UploadMetadata meta, FileType type, byte[] content) {
        return store(createdBy, meta, validatedRoles(meta), type, content);
    }

    private Document store(UUID createdBy, UploadMetadata meta, List<String> roles, FileType type, byte[] content) {
        if (documents.existsByCode(meta.code())) {
            throw ApiException.conflict("A document with this code already exists.");
        }
        UUID id = UUID.randomUUID();
        String key = "documents/" + id + "." + type.name().toLowerCase();
        Document doc = new Document(id, meta.code(), meta.title(), type, meta.category(), meta.kb(), meta.classification(),
                meta.ownerId(), roles, key, sha256Hex(content), content.length, createdBy, Instant.now(clock));
        storage.put(key, content, contentTypeOf(type));
        try {
            return documents.saveAndFlush(doc);
        } catch (DataIntegrityViolationException e) {
            storage.delete(key);
            String msg = String.valueOf(e.getMostSpecificCause().getMessage());
            if (msg.contains("documents_code_key")) {
                throw ApiException.conflict("A document with this code already exists.");
            }
            throw ApiException.unprocessable("The document metadata violates an integrity rule (check owner and allowed roles).");
        } catch (RuntimeException e) {
            storage.delete(key);
            throw e;
        }
    }

    /** Mismas reglas que los CHECK de la BD, para dar un 422 claro en vez de un fallo de integridad. MANAGER siempre incluido. */
    private static List<String> validatedRoles(UploadMetadata meta) {
        if (meta.kb() == null || meta.category() == null || meta.classification() == null) {
            throw ApiException.unprocessable("kb, category and classification are required.");
        }
        Set<String> roles = new LinkedHashSet<>();
        roles.add(Role.MANAGER.name());
        if (meta.allowedRoles() != null) {
            meta.allowedRoles().forEach(r -> roles.add(r.name()));
        }
        boolean employeeRole = roles.contains(Role.EMPLOYEE.name());
        if (meta.classification() == Classification.RESTRICTED && employeeRole) {
            throw ApiException.unprocessable("RESTRICTED documents cannot be readable by the EMPLOYEE role.");
        }
        if (meta.kb() == Kb.EMPLEADOS && (meta.ownerId() == null || employeeRole)) {
            throw ApiException.unprocessable("Employee knowledge-base documents need an owner and cannot include the EMPLOYEE role.");
        }
        return List.copyOf(roles);
    }

    /** Carga el documento solo si la política lo permite; si no, audita ACCESS_DENIED y responde el 404 genérico. */
    private Document loadAuthorized(Subject subject, UUID id, Action action) {
        Specification<Document> byId = (root, q, cb) -> cb.equal(root.get("id"), id);
        var found = documents.findOne(byId.and(policy.<Document>toSpecification(subject, action)));
        if (found.isPresent()) {
            Document doc = found.get();
            // Defensa en profundidad: la decisión sobre el objeto cargado debe coincidir con el filtro de la consulta.
            if (policy.decide(subject, action, attributes(doc)).allowed()) {
                return doc;
            }
        }
        // El motivo real solo va a la auditoría.
        ResourceAttributes attrs = documents.findById(id).map(DocumentService::attributes).orElse(null);
        Decision decision = policy.decide(subject, action, attrs);
        String reason = attrs == null ? "RESOURCE_NOT_FOUND" : decision.reasonCode();
        audit.publish(new AuditEvent(AuditAction.ACCESS_DENIED, subject.userId(), RESOURCE_TYPE, id.toString(), false, reason,
                AuditEvent.Channel.REST, Map.of("action", action.name())));
        throw ApiException.notFound();
    }

    private DocumentContent open(Document doc) {
        InputStream stream = storage.get(doc.getStorageKey());
        return new DocumentContent(stream, contentTypeOf(doc.getFileType()),
                doc.getCode() + "." + doc.getFileType().name().toLowerCase(), doc.getSizeBytes());
    }

    private static AuditEvent allowed(AuditAction action, Subject subject, Document doc, String reason) {
        return new AuditEvent(action, subject.userId(), RESOURCE_TYPE, doc.getId().toString(), true, reason,
                AuditEvent.Channel.REST, Map.of("code", doc.getCode()));
    }

    private static ResourceAttributes attributes(Document d) {
        return new ResourceAttributes(d.getAllowedRoles(), d.getOwnerId(), d.getDeletedAt() != null);
    }

    private static <V> Specification<Document> equalsIfPresent(String attribute, V value) {
        return (root, q, cb) -> value == null ? cb.conjunction() : cb.equal(root.get(attribute), value);
    }

    static String contentTypeOf(FileType type) {
        return switch (type) {
            case PDF -> "application/pdf";
            case XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case CSV -> "text/csv; charset=UTF-8";
            case JSON -> "application/json";
        };
    }

    private static String sha256Hex(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DocumentSummary toSummary(Document d) {
        return new DocumentSummary(d.getId(), d.getCode(), d.getTitle(), d.getFileType(), d.getCategory(), d.getKb(),
                d.getClassification(), d.getSizeBytes(), d.getCreatedAt());
    }

    private static DocumentDetail toDetail(Document d) {
        return new DocumentDetail(d.getId(), d.getCode(), d.getTitle(), d.getFileType(), d.getCategory(), d.getKb(),
                d.getClassification(), d.getOwnerId(), d.getSizeBytes(), d.getChecksumSha256(), d.getIndexStatus(),
                d.getFileType() == FileType.PDF, d.getFileType() == FileType.XLSX, d.getCreatedAt(), d.getUpdatedAt());
    }
}
