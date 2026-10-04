package com.example.javaaiagent.eval;

import com.example.javaaiagent.config.OpenAiSettings;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Map;

record EvalConfig(OpenAiSettings endpoint, String model, String key, int repetitions,
                  BigDecimal inputUsdPerMillion, BigDecimal outputUsdPerMillion) {
    static EvalConfig from(Map<String, String> env) {
        String key = required(env, "OPENAI_API_KEY");
        String model = required(env, "EVAL_MODEL");
        var endpoint = new OpenAiSettings(URI.create(required(env, "EVAL_BASE_URL")),
                env.getOrDefault("OPENAI_COMPLETIONS_PATH", "/v1/chat/completions"));
        String host = endpoint.baseUrl().getHost();
        if (!endpoint.baseUrl().getScheme().equals("https")
                && !java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(host)) {
            throw new IllegalArgumentException("Evaluation endpoints require HTTPS except on loopback");
        }
        int repetitions;
        try { repetitions = Integer.parseInt(env.getOrDefault("EVAL_REPETITIONS", "1")); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Invalid EVAL_REPETITIONS"); }
        if (repetitions < 1 || repetitions > 5) throw new IllegalArgumentException("EVAL_REPETITIONS must be 1–5");
        BigDecimal input = price(env.get("EVAL_INPUT_USD_PER_MILLION"));
        BigDecimal output = price(env.get("EVAL_OUTPUT_USD_PER_MILLION"));
        if ((input == null) != (output == null)) throw new IllegalArgumentException("Supply both evaluation token prices or neither");
        return new EvalConfig(endpoint, model, key, repetitions, input, output);
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required for live evaluation");
        return value;
    }

    private static BigDecimal price(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            BigDecimal price = new BigDecimal(value);
            if (price.signum() < 0 || price.compareTo(new BigDecimal("1000000")) > 0) throw new NumberFormatException();
            return price;
        } catch (NumberFormatException ex) { throw new IllegalArgumentException("Invalid evaluation token price"); }
    }

    @Override public String toString() { return "EvalConfig[redacted]"; }
}
