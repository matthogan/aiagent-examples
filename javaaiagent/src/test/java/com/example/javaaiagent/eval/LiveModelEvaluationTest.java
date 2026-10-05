package com.example.javaaiagent.eval;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.javaaiagent.config.JsonSchemaSettings;
import com.example.javaaiagent.config.TemplateSettings;
import com.example.javaaiagent.http.BoundedHttp;
import com.example.javaaiagent.model.SpringAiAgentModel;
import com.example.javaaiagent.templates.MessageTemplates;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

/** Explicit opt-in: the regular test suite never uses credentials or calls a live model. */
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_EVALS", matches = "true")
class LiveModelEvaluationTest {
    @Test
    void evaluateRealProvider() throws Exception {
        EvalConfig config = EvalConfig.from(System.getenv());
        EvalDataset dataset = EvalDataset.load();
        var templates = new MessageTemplates(new TemplateSettings(System.getenv().getOrDefault(
                "MESSAGE_TEMPLATES_LOCATION", MessageTemplates.DEFAULT_LOCATION)), EvalScorer.JSON, new DefaultResourceLoader());
        var timeouts = com.example.javaaiagent.TestTimeouts.defaults();
        var http = BoundedHttp.client(timeouts.connect());
        Instant started = Instant.now();
        Path reportPath = Path.of("target/evals/live-" + started.toString().replace(':', '-') + ".json");
        var results = new ArrayList<EvalRunner.Result>();
        Map<String, Object> report = EvalReport.create(dataset, config, templates, results, started);
        EvalReport.write(reportPath, report);
        try {
            var requestFactory = new JdkClientHttpRequestFactory(http);
            requestFactory.setReadTimeout(timeouts.llmCall());
            var api = OpenAiApi.builder().baseUrl(config.endpoint().baseUrl().toString())
                    .completionsPath(config.endpoint().completionsPath()).apiKey(config.key())
                    .restClientBuilder(RestClient.builder().requestFactory(requestFactory)).build();
            var provider = OpenAiChatModel.builder().openAiApi(api)
                    .defaultOptions(OpenAiChatOptions.builder().model(config.model()).maxTokens(1000)
                            .internalToolExecutionEnabled(false).build())
                    .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
            var jsonSchemaSettings =
                    new JsonSchemaSettings(
                            new ClassPathResource("schemas/observation-response.json"));
            var runner = new EvalRunner(dataset, templates,
                    observer -> SpringAiAgentModel.create(provider, templates, jsonSchemaSettings, observer), config);
            for (int repetition = 1; repetition <= config.repetitions(); repetition++) {
                for (var scenario : dataset.cases()) {
                    results.add(runner.run(scenario, repetition));
                    report = EvalReport.create(dataset, config, templates, results, started);
                    EvalReport.write(reportPath, report);
                    System.out.println("EVAL " + scenario.id() + " repetition=" + repetition
                            + " passed=" + results.getLast().passed());
                }
            }
        } finally {
            http.shutdownNow();
        }
        System.out.println("Evaluation report: " + reportPath.toAbsolutePath());
        assertTrue(Boolean.TRUE.equals(report.get("allPassed")), "Real-model evaluation failed; inspect " + reportPath);
    }
}
