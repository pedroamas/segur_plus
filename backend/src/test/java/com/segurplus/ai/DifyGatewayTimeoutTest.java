package com.segurplus.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.segurplus.support.FakeDify;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Plazos del ADR 0003 sobre el adaptador (sin Spring): primera respuesta, inactividad, tope y reintento antes del primer token. */
class DifyGatewayTimeoutTest {

    private DifyAiGateway gateway(String fallbackKey, Duration first, Duration inactivity, Duration total) {
        AiProperties props = new AiProperties(new AiProperties.Dify(FakeDify.baseUrl(), FakeDify.APP_KEY, fallbackKey, FakeDify.PLANNER_KEY, null,
                FakeDify.KNOWLEDGE_KEY, "SegurPlus ", Duration.ofSeconds(1), first, inactivity, total), null, null, null, null);
        return new DifyAiGateway(props, new ObjectMapper());
    }

    @BeforeEach
    void reset() {
        FakeDify.reset();
    }

    @Test
    void noFirstTokenWithinTheLimitIsATimeoutAndRetriedOnceWithTheFallbackApp() {
        FakeDify.SERVER.stubFor(post(urlEqualTo("/v1/chat-messages")).withHeader("Authorization", equalTo("Bearer " + FakeDify.APP_KEY))
                .willReturn(aResponse().withFixedDelay(2500).withHeader("Content-Type", "text/event-stream").withBody("data: {\"event\":\"message_end\"}\n\n")));
        FakeDify.SERVER.stubFor(post(urlEqualTo("/v1/chat-messages")).withHeader("Authorization", equalTo("Bearer fallback-key"))
                .willReturn(aResponse().withHeader("Content-Type", "text/event-stream")
                        .withBody("data: {\"event\":\"message\",\"answer\":\"desde la reserva\"}\n\ndata: {\"event\":\"message_end\",\"metadata\":{\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4}}}\n\n")));
        List<String> text = new ArrayList<>();
        AiGateway.Usage usage = gateway("fallback-key", Duration.ofMillis(800), Duration.ofSeconds(5), Duration.ofSeconds(20)).generate("q", "u_x", text::add);
        assertThat(text).containsExactly("desde la reserva");
        assertThat(usage).isEqualTo(new AiGateway.Usage(3, 4));
        assertThat(FakeDify.chatRequests(FakeDify.APP_KEY)).hasSize(1);
        assertThat(FakeDify.chatRequests("fallback-key")).hasSize(1);
    }

    @Test
    void timeoutOnBothAttemptsFailsWithTimeoutBeforeStarting() {
        FakeDify.SERVER.stubFor(post(urlEqualTo("/v1/chat-messages")).willReturn(aResponse().withFixedDelay(2500).withHeader("Content-Type", "text/event-stream").withBody("data: {}\n\n")));
        assertThatThrownBy(() -> gateway(null, Duration.ofMillis(500), Duration.ofSeconds(5), Duration.ofSeconds(20)).generate("q", "u_x", t -> { }))
                .isInstanceOfSatisfying(AiException.class, e -> {
                    assertThat(e.kind()).isEqualTo(AiException.Kind.TIMEOUT);
                    assertThat(e.started()).isFalse();
                });
    }

    @Test
    void afterTheFirstTokenThereIsNoRetryAndSilenceBeyondInactivityFails() {
        FakeDify.SERVER.stubFor(post(urlEqualTo("/v1/chat-messages")).withHeader("Authorization", equalTo("Bearer " + FakeDify.APP_KEY))
                .willReturn(aResponse().withHeader("Content-Type", "text/event-stream").withChunkedDribbleDelay(8, 3200)
                        .withBody("data: {\"event\":\"message\",\"answer\":\"hola\"}\n\ndata: {\"event\":\"message_end\"}\n\n")));
        List<String> text = new ArrayList<>();
        assertThatThrownBy(() -> gateway("fallback-key", Duration.ofSeconds(5), Duration.ofMillis(400), Duration.ofSeconds(20)).generate("q", "u_x", text::add))
                .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.started()).as("texto ya emitido: sin reintento").isTrue());
        assertThat(FakeDify.chatRequests("fallback-key")).as("sin reintento una vez emitido texto").isEmpty();
    }

    @Test
    void connectionRefusedIsUnavailableWithoutLeakingTheKey() {
        AiProperties props = new AiProperties(new AiProperties.Dify("http://127.0.0.1:1", FakeDify.APP_KEY, null, FakeDify.PLANNER_KEY, null,
                FakeDify.KNOWLEDGE_KEY, "SegurPlus ", Duration.ofMillis(500), null, null, null), null, null, null, null);
        assertThatThrownBy(() -> new DifyAiGateway(props, new ObjectMapper()).generate("q", "u", t -> { }))
                .isInstanceOfSatisfying(AiException.class, e -> {
                    assertThat(e.kind()).isEqualTo(AiException.Kind.UNAVAILABLE);
                    assertThat(String.valueOf(e.getMessage())).doesNotContain(FakeDify.APP_KEY);
                });
    }

    @Test
    void errorEventFromDifyAbortsWithAnAiException() {
        FakeDify.SERVER.stubFor(post(urlEqualTo("/v1/chat-messages")).willReturn(aResponse().withHeader("Content-Type", "text/event-stream")
                .withBody("data: {\"event\":\"error\",\"status\":500,\"code\":\"completion_request_error\",\"message\":\"SECRETO-DEL-MODELO\"}\n\n")));
        assertThatThrownBy(() -> gateway(null, Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(10)).generate("q", "u", t -> { }))
                .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.getMessage()).doesNotContain("SECRETO"));
    }
}
