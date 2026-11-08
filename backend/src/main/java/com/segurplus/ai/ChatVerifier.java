package com.segurplus.ai;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verificación posterior (ADR 0003, 04-chat-flow): señal de calidad, NO un bloqueo (el LLM solo vio texto autorizado).
 * Comprueba que (1) las citas {@code [D04]} del texto están entre los documentos autorizados del contexto y (2) las cifras del
 * texto aparecen en las salidas de herramientas, en los fragmentos autorizados o en la propia pregunta/historial.
 */
final class ChatVerifier {

    private static final Pattern CITATION = Pattern.compile("\\[([A-Z][A-Z0-9]{1,7})]");
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d.,]*\\d|\\d");

    private ChatVerifier() {
    }

    record Verification(boolean citationsValid, List<String> citedCodes, List<String> unknownCitations, int numbersChecked,
            List<String> unsupportedNumbers) {

        boolean ok() {
            return citationsValid && unsupportedNumbers.isEmpty();
        }
    }

    static Verification verify(String answer, Set<String> authorizedCodes, List<String> groundingTexts) {
        Set<String> cited = new LinkedHashSet<>();
        Matcher m = CITATION.matcher(answer);
        while (m.find()) {
            cited.add(m.group(1));
        }
        List<String> unknown = cited.stream().filter(c -> !authorizedCodes.contains(c)).toList();
        Set<String> grounded = new LinkedHashSet<>();
        for (String text : groundingTexts) {
            numbers(text).forEach(n -> grounded.add(canonical(n)));
        }
        List<String> unsupported = new ArrayList<>();
        int checked = 0;
        for (String n : numbers(CITATION.matcher(answer).replaceAll(" "))) {
            if (n.length() < 2 && !n.contains(",")) {
                continue; // dígitos sueltos (listas, «1 póliza»): ruido
            }
            checked++;
            if (!grounded.contains(canonical(n)) && !unsupported.contains(n)) {
                unsupported.add(n);
            }
        }
        return new Verification(unknown.isEmpty(), List.copyOf(cited), unknown, checked, List.copyOf(unsupported));
    }

    private static List<String> numbers(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = NUMBER.matcher(text == null ? "" : text);
        while (m.find()) {
            out.add(m.group().replaceAll("[.,]+$", ""));
        }
        return out;
    }

    /** Forma canónica: separador decimal ES («80,7») o EN («80.7») y miles («9.680,00», «9,680.00») ⇒ el mismo número. */
    static String canonical(String n) {
        String s = n;
        int comma = s.lastIndexOf(',');
        int dot = s.lastIndexOf('.');
        if (comma >= 0 && dot >= 0) {
            char decimal = comma > dot ? ',' : '.';
            s = s.replace(decimal == ',' ? "." : ",", "").replace(decimal, '.');
        } else if (comma >= 0) {
            s = isThousands(s, ',') ? s.replace(",", "") : s.replace(',', '.');
        } else if (dot >= 0 && isThousands(s, '.')) {
            s = s.replace(".", "");
        }
        try {
            BigDecimal v = new BigDecimal(s).stripTrailingZeros();
            return v.toPlainString();
        } catch (NumberFormatException e) {
            return n;
        }
    }

    private static boolean isThousands(String s, char sep) {
        return s.matches("\\d{1,3}(\\" + sep + "\\d{3})+");
    }
}
