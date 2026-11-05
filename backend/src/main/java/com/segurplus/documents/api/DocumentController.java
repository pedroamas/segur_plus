package com.segurplus.documents.api;

import com.segurplus.auth.Role;
import com.segurplus.auth.Subject;
import com.segurplus.documents.DocCategory;
import com.segurplus.documents.Classification;
import com.segurplus.documents.DocumentContent;
import com.segurplus.documents.DocumentService;
import com.segurplus.documents.FileType;
import com.segurplus.documents.Kb;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * El usuario sale SIEMPRE del {@link Subject} (JWT). Ningún endpoint lee {@code employee_id}, {@code role} ni similares del
 * cliente. Recurso no autorizado ⇒ 404 idéntico al de uno inexistente.
 */
@RestController
@RequestMapping("/api/v1/documents")
@Tag(name = "documents")
class DocumentController {

    private final DocumentService service;

    DocumentController(DocumentService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Listado filtrado por la política de acceso (en la consulta)")
    DocumentPage list(Subject subject, @RequestParam(required = false) Kb kb, @RequestParam(required = false) DocCategory category,
            @RequestParam(required = false) FileType fileType, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(subject, kb, category, fileType, page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Detalle (404 si no existe o no está autorizado)")
    DocumentDetail get(Subject subject, @PathVariable UUID id) {
        return service.get(subject, id);
    }

    @GetMapping("/{id}/view")
    @Operation(summary = "Visor PDF (entrega inline)")
    ResponseEntity<InputStreamResource> view(Subject subject, @PathVariable UUID id) {
        return stream(service.view(subject, id), ContentDisposition.inline());
    }

    @GetMapping("/{id}/preview")
    @Operation(summary = "Preview de Excel renderizado en el servidor (valores calculados, con límites)")
    ExcelPreview preview(Subject subject, @PathVariable UUID id) {
        return service.preview(subject, id);
    }

    @GetMapping("/{id}/download")
    @Operation(summary = "Descarga auditada (DOCUMENT_DOWNLOAD)")
    ResponseEntity<InputStreamResource> download(Subject subject, @PathVariable UUID id) {
        return stream(service.download(subject, id), ContentDisposition.attachment());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Subida (solo MANAGER): valida tipo real, extensión y tamaño; guarda en MinIO privado")
    ResponseEntity<DocumentDetail> upload(Subject subject, @RequestPart("file") MultipartFile file, @RequestParam String code,
            @RequestParam String title, @RequestParam Kb kb, @RequestParam DocCategory category,
            @RequestParam Classification classification, @RequestParam(required = false) UUID ownerId,
            @RequestParam(required = false) List<Role> allowedRoles) throws IOException {
        DocumentDetail created = service.upload(subject,
                new UploadMetadata(code, title, kb, category, classification, ownerId, allowedRoles),
                file.getOriginalFilename(), file.getBytes());
        return ResponseEntity.created(URI.create("/api/v1/documents/" + created.id())).body(created);
    }

    private static ResponseEntity<InputStreamResource> stream(DocumentContent content, ContentDisposition.Builder disposition) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.filename(content.filename()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                // El PDF servido inline no puede ejecutar scripts ni cargar recursos del origen de la API.
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .body(new InputStreamResource(content.stream()));
    }
}
