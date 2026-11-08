package com.segurplus.ai.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Cuerpo de {@code POST /api/v1/chat}. SOLO {@code conversationId} (opcional) y {@code message}. Cualquier otro campo
 * ({@code employee_id}, {@code role}, {@code assistantId}, {@code datasetId}…) se IGNORA —la identidad sale del JWT— y solo se
 * deja constancia de su NOMBRE en la auditoría (nunca de su valor).
 */
@Schema(description = "Solo conversationId y message; los demás campos se ignoran.")
public final class ChatRequest {

    private static final Set<String> IDENTITY_FIELDS = Set.of("employee_id", "employeeid", "role", "assistantid", "datasetid", "user_id",
            "userid", "dataset_id", "assistant_id", "user");

    @Schema(description = "Conversación propia a continuar; si falta se crea una nueva. Ajena o inexistente ⇒ 404.")
    private UUID conversationId;
    @Schema(description = "Pregunta del usuario (máx. 2000 caracteres).")
    private String message;
    @JsonIgnore
    private final Set<String> ignoredIdentityFields = new TreeSet<>();

    @JsonProperty("conversationId")
    public UUID conversationId() {
        return conversationId;
    }

    @JsonProperty("conversationId")
    public void setConversationId(UUID conversationId) {
        this.conversationId = conversationId;
    }

    @JsonProperty("message")
    public String message() {
        return message;
    }

    @JsonProperty("message")
    public void setMessage(String message) {
        this.message = message;
    }

    @JsonAnySetter
    void unknown(String name, Object ignoredValue) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (IDENTITY_FIELDS.contains(n)) {
            ignoredIdentityFields.add(n);
        }
    }

    /** Nombres (no valores) de los campos de identidad que envió el cliente y se ignoraron. */
    @JsonIgnore
    @Schema(hidden = true)
    public Set<String> ignoredIdentityFields() {
        return Set.copyOf(ignoredIdentityFields);
    }
}
