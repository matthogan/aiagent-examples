package com.example.javaaiagent.templates;

import com.example.javaaiagent.config.TemplateSettings;
import com.example.javaaiagent.http.StrictJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads once at startup and renders data, not executable expressions.
 */
@Component
public final class MessageTemplates {

    public static final String DEFAULT_LOCATION = "classpath:templates/messages.json";
    private static final int MAX_FILE_BYTES = 65_536;
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([a-zA-Z][a-zA-Z0-9]*)}}");
    private final ObjectMapper json;
    private final ObjectNode catalog;

    public MessageTemplates(TemplateSettings settings, ObjectMapper json, ResourceLoader resources) {
        this.json = json;
        catalog = read(resources, DEFAULT_LOCATION);
        if (!DEFAULT_LOCATION.equals(settings.location())) {
            applyOverrides(read(resources, settings.location()));
        }
        validateCatalog();
    }

    public String text(String name) {
        return text(name, Map.of());
    }

    /**
     * Identifies the effective catalog, including overrides, without exposing prompt contents.
     */
    public String fingerprint() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(catalog.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public String text(String name, Map<String, ?> values) {
        JsonNode rendered = render(name, values);
        if (!rendered.isTextual()) {
            throw new IllegalArgumentException("Expected text template: " + name);
        }
        return rendered.textValue();
    }

    public String json(String name) {
        return json(name, Map.of());
    }

    public String json(String name, Map<String, ?> values) {
        return render(name, values).toString();
    }

    public JsonNode render(String name, Map<String, ?> values) {
        JsonNode template = catalog.get(name);
        if (template == null) {
            throw new IllegalArgumentException("Unknown template: " + name);
        }
        if (!TemplateContract.named(name).parameters.equals(values.keySet())) {
            throw new TemplateRenderException("Incorrect template variables: " + name);
        }
        JsonNode result = substitute(template, values);
        var kind = TemplateContract.named(name).kind;
        boolean tooLarge = kind == TemplateContract.Kind.TEXT
                ? !result.isTextual() || result.textValue().length() > 16_000
                : result.toString().getBytes(StandardCharsets.UTF_8).length > 8192;
        if (tooLarge) {
            throw new TemplateRenderException("Rendered template exceeds output contract: " + name);
        }
        return result;
    }

    private ObjectNode read(ResourceLoader resources, String location) {
        try (var stream = resources.getResource(location).getInputStream()) {
            byte[] bytes = stream.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("Template file exceeds 64 KiB");
            }
            JsonNode node = StrictJson.reader(json).readValue(bytes);
            if (!(node instanceof ObjectNode object)) {
                throw new IllegalArgumentException("Expected template object");
            }
            return object;
        } catch (Exception ex) {
            // Fail startup with a safe description instead of echoing potentially sensitive file contents.
            throw new IllegalArgumentException("Cannot load template catalog; check location, size and JSON syntax");
        }
    }

    private void applyOverrides(ObjectNode overrides) {
        overrides.fields().forEachRemaining(entry -> {
            if (!TemplateContract.keys().contains(entry.getKey())) {
                throw new IllegalArgumentException("Unknown template override: " + entry.getKey());
            }
            catalog.set(entry.getKey(), entry.getValue().deepCopy());
        });
    }

    /**
     * Templates can change wording and data presentation, but must preserve their input contract.
     */
    private void validateCatalog() {
        Set<String> actual = new HashSet<>();
        catalog.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(TemplateContract.keys())) {
            throw new IllegalArgumentException("Incomplete template catalog");
        }
        catalog.fields().forEachRemaining(entry -> validateTemplate(entry.getKey(), entry.getValue()));
        JsonNode schema = catalog.get("service-schema");
        if (!schema.path("type").asText().equals("object") || !schema.path("properties").isObject()) {
            throw new IllegalArgumentException("service-schema requires an object schema with properties");
        }
    }

    private void validateTemplate(String name, JsonNode template) {
        var contract = TemplateContract.named(name);
        boolean validType = switch (contract.kind) {
            case VALUE -> !template.isNull();
            case OBJECT -> template.isObject();
            case TEXT -> template.isTextual() && !template.textValue().isBlank();
        };
        if (!validType || !variables(template).equals(contract.parameters)
                || template.toString().getBytes(StandardCharsets.UTF_8).length > 16_384) {
            throw new IllegalArgumentException("Invalid template type, placeholders or size: " + name);
        }
        // Static JSON must already fit the runtime JSON limit; reject unusable overrides early.
        if (contract.kind == TemplateContract.Kind.OBJECT && template.toString().getBytes(StandardCharsets.UTF_8).length > 8192) {
            throw new IllegalArgumentException("JSON template exceeds 8 KiB: " + name);
        }
        if (template.isTextual() && template.textValue().length() > 16_000) {
            throw new IllegalArgumentException("Text template exceeds 16000 characters: " + name);
        }
        if (name.startsWith("error-") && !template.path("error").isTextual()) {
            throw new IllegalArgumentException("Error template requires an error string: " + name);
        }
    }

    private Set<String> variables(JsonNode node) {
        Set<String> names = new HashSet<>();
        if (node.isTextual()) {
            var matcher = VARIABLE.matcher(node.textValue());
            while (matcher.find()) names.add(matcher.group(1));
            String remaining = matcher.replaceAll("");
            if (remaining.contains("{{") || remaining.contains("}}")) {
                throw new IllegalArgumentException("Invalid template placeholder syntax");
            }
        } else if (node.isContainerNode()) {
            node.forEach(child -> names.addAll(variables(child)));
            if (node.isObject())
                node.fieldNames().forEachRemaining(key -> {
                    if (key.contains("{{") || key.contains("}}")) {
                        throw new IllegalArgumentException(
                                "Template variables are not allowed in field names");
                    }
                });
        }
        return names;
    }

    private JsonNode substitute(JsonNode node, Map<String, ?> values) {
        if (node.isObject()) {
            var result = json.createObjectNode();
            node.fields().forEachRemaining(field ->
                    result.set(field.getKey(), substitute(field.getValue(), values)));
            return result;
        }
        if (node.isArray()) {
            var result = json.createArrayNode();
            node.forEach(child -> result.add(substitute(child, values)));
            return result;
        }
        if (!node.isTextual()) {
            return node.deepCopy();
        }
        var matcher = VARIABLE.matcher(node.textValue());
        // Whole-value placeholders preserve JSON types. Jackson handles quotes and control
        // characters.
        if (matcher.matches()) {
            return json.valueToTree(values.get(matcher.group(1)));
        }
        String rendered = matcher.replaceAll(match ->
                Matcher.quoteReplacement(String.valueOf(values.get(match.group(1)))));
        // Substitution is one pass: user text containing {{...}} is never interpreted a second time.
        return json.getNodeFactory().textNode(rendered);
    }
}
