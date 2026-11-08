package com.segurplus.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ChatVerifierTest {

    @Test
    void numbersInEsAndEnFormatsCanonicalizeToTheSameValue() {
        assertThat(ChatVerifier.canonical("9.680,00")).isEqualTo(ChatVerifier.canonical("9680.00")).isEqualTo("9680");
        assertThat(ChatVerifier.canonical("80,7")).isEqualTo(ChatVerifier.canonical("80.7")).isEqualTo("80.7");
        assertThat(ChatVerifier.canonical("12.000")).isEqualTo("12000");
        assertThat(ChatVerifier.canonical("968")).isEqualTo(ChatVerifier.canonical("968.00"));
    }

    @Test
    void figuresPresentInTheGroundingAreSupportedAndTheRestAreFlagged() {
        var v = ChatVerifier.verify("Cumplimiento del 80,7 % con 9.680 € de 12.000 €; además 4.321 € inventados.", Set.of(),
                List.of("{\"compliancePercent\":\"80.7\",\"premiums\":\"9680.00\",\"target\":\"12000.00\"}"));
        assertThat(v.unsupportedNumbers()).containsExactly("4.321");
        assertThat(v.ok()).isFalse();
        assertThat(v.numbersChecked()).isEqualTo(4);
    }

    @Test
    void citationsMustBeAmongTheAuthorizedDocuments() {
        var ok = ChatVerifier.verify("Véase [D04] y [G01].", Set.of("D04", "G01"), List.of());
        assertThat(ok.citationsValid()).isTrue();
        assertThat(ok.citedCodes()).containsExactly("D04", "G01");
        var bad = ChatVerifier.verify("Véase [D04] y [D14].", Set.of("D04"), List.of());
        assertThat(bad.citationsValid()).isFalse();
        assertThat(bad.unknownCitations()).containsExactly("D14");
        assertThat(bad.ok()).isFalse();
    }

    @Test
    void codesInsideCitationMarkersAreNotCountedAsFigures() {
        var v = ChatVerifier.verify("Según [D04] el plan es válido.", Set.of("D04"), List.of());
        assertThat(v.numbersChecked()).isZero();
        assertThat(v.ok()).isTrue();
    }

    @Test
    void singleDigitsAreNoise() {
        assertThat(ChatVerifier.verify("Hay 3 opciones: 1) a 2) b", Set.of(), List.of()).unsupportedNumbers()).isEmpty();
    }
}
