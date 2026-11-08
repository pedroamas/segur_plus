package com.segurplus.ai;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración de la IA. Las claves de Dify (aplicación y Knowledge) solo existen en el backend (ADR 0003 §9) y nunca se
 * registran. El perfil del asistente por rol está en {@code assistant-profile.yml}.
 */
@ConfigurationProperties("segurplus.ai")
public record AiProperties(Dify dify, Indexer indexer, Retrieval retrieval, Chat chat, Map<String, Profile> profiles) {

    public AiProperties {
        dify = dify == null ? new Dify(null, null, null, null, null, null, null, null, null, null, null) : dify;
        indexer = indexer == null ? new Indexer(true, 30, 300, 120, 900) : indexer;
        retrieval = retrieval == null ? new Retrieval(5, 8, 0.0) : retrieval;
        chat = chat == null ? new Chat(2000, 6) : chat;
        profiles = profiles == null ? Map.of() : profiles;
    }

    /**
     * Timeouts del ADR 0003: conexión 3 s, primera respuesta 10 s, inactividad 30 s, tope 120 s. {@code blockingTimeout}
     * acota las llamadas no streaming (plan, recuperación, metadatos) y {@code indexTimeout} la creación de documentos.
     */
    public record Dify(String baseUrl, String appApiKey, String appApiKeyFallback, String plannerApiKey, String plannerApiKeyFallback,
            String knowledgeApiKey, String datasetPrefix, Duration connectTimeout, Duration firstResponseTimeout,
            Duration inactivityTimeout, Duration totalTimeout) {

        public Dify {
            datasetPrefix = blank(datasetPrefix) ? "SegurPlus " : datasetPrefix;
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
            firstResponseTimeout = firstResponseTimeout == null ? Duration.ofSeconds(10) : firstResponseTimeout;
            inactivityTimeout = inactivityTimeout == null ? Duration.ofSeconds(30) : inactivityTimeout;
            totalTimeout = totalTimeout == null ? Duration.ofSeconds(120) : totalTimeout;
        }

        public boolean configured() {
            return !blank(baseUrl) && !blank(knowledgeApiKey) && !blank(appApiKey);
        }

        private static boolean blank(String s) {
            return s == null || s.isBlank();
        }

        /** Nunca imprime claves. */
        @Override
        public String toString() {
            return "Dify[baseUrl=" + baseUrl + ", keys=hidden]";
        }
    }

    /** Indexador: job de reintento cada {@code intervalSeconds}; FAILED se reintenta pasados {@code retryAfterSeconds}. */
    public record Indexer(boolean enabled, long intervalSeconds, long retryAfterSeconds, long pollTimeoutSeconds,
            long stuckAfterSeconds) {
    }

    /** {@code topK} por KB; {@code maxContextChunks} tras re-autorizar; umbral de score opcional. */
    public record Retrieval(int topK, int maxContextChunks, double scoreThreshold) {
    }

    public record Chat(int maxMessageChars, int historyTurns) {
    }

    /**
     * Perfil por rol. {@code unfiltered}: sin filtro de metadatos (MANAGER). En otro caso el filtro exige
     * {@code allowed_roles} ∋ rol {@code OR} (si {@code ownerFilter}) {@code owner_id} = usuario. Es una optimización: la
     * autorización real es la re-autorización de cada fragmento.
     */
    public record Profile(List<String> kbs, List<String> tools, boolean unfiltered, boolean ownerFilter) {

        public Profile {
            kbs = kbs == null ? List.of() : List.copyOf(kbs);
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }
}
