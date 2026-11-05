package com.segurplus.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.Yaml;

/**
 * Oráculo INDEPENDIENTE de la política: carga permissions-spec.yaml (fuente declarativa) e implementa la fórmula de
 * docs/data-model/permissions-mapping.md §1 sin usar nada de la aplicación:
 * {@code READ = DOWNLOAD = rol ∈ allowed_roles ∨ owner = usuario}.
 */
public final class PermissionsOracle {

    public record Doc(String code, String kb, String owner, List<String> allowedRoles) {
    }

    public static final Path SPEC = Path.of("..", "docs", "data-model", "permissions-spec.yaml");

    private final Map<String, String> roleByUser = new LinkedHashMap<>();
    private final Map<String, Doc> docs = new LinkedHashMap<>();
    private final Map<String, Object> raw;

    @SuppressWarnings("unchecked")
    public PermissionsOracle() {
        try (var in = Files.newInputStream(SPEC)) {
            raw = new Yaml().load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + SPEC.toAbsolutePath(), e);
        }
        ((Map<String, Map<String, String>>) raw.get("users")).forEach((code, u) -> roleByUser.put(code, u.get("role")));
        for (Map<String, Object> d : (List<Map<String, Object>>) raw.get("documents")) {
            docs.put((String) d.get("code"), new Doc((String) d.get("code"), (String) d.get("kb"), (String) d.get("owner"),
                    (List<String>) d.get("allowed_roles")));
        }
    }

    public Set<String> users() {
        return roleByUser.keySet();
    }

    public String roleOf(String user) {
        return roleByUser.get(user);
    }

    public Map<String, Doc> docs() {
        return docs;
    }

    public boolean canRead(String user, String docCode) {
        Doc d = docs.get(docCode);
        return d.allowedRoles().contains(roleByUser.get(user)) || user.equals(d.owner());
    }

    public Set<String> readable(String user) {
        return docs.keySet().stream().filter(c -> canRead(user, c)).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    /** Controles positivos obligatorios del YAML: usuario -> documentos (READ y DOWNLOAD). */
    @SuppressWarnings("unchecked")
    public Map<String, List<String>> positiveCases() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map<String, Object> c : (List<Map<String, Object>>) raw.get("positive_cases")) {
            out.put((String) c.get("user"), (List<String>) c.get("docs"));
        }
        return out;
    }
}
