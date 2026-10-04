package com.example.javaaiagent.bootstrap;

import com.example.javaaiagent.application.AgentModel;
import com.example.javaaiagent.config.AgentSettings;
import com.example.javaaiagent.config.OpenAiSettings;
import com.example.javaaiagent.config.RuntimeSettings;
import com.example.javaaiagent.config.TimeoutSettings;
import com.example.javaaiagent.http.BoundedHttp;
import com.example.javaaiagent.model.DemoAgentModel;
import com.example.javaaiagent.model.SpringAiAgentModel;
import com.example.javaaiagent.model.TimedAgentModel;
import com.example.javaaiagent.templates.MessageTemplates;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
public class ModelConfiguration {

    @Bean(destroyMethod = "shutdownNow")
    HttpClient modelHttpClient(TimeoutSettings timeouts) {
        return BoundedHttp.client(timeouts.connect());
    }

    @Bean
    AgentModel agentModel(AgentSettings settings, TimeoutSettings timeouts, OpenAiSettings endpoint,
                          MessageTemplates templates, RuntimeSettings runtime, @Qualifier("modelHttpClient") HttpClient http) {

        if (settings.environment().equals("production") && !endpoint.baseUrl().getScheme().equals("https")) {
            throw new IllegalArgumentException("Production OPENAI_BASE_URL requires HTTPS");
        }
        if (settings.modelMode().equals("demo")) {
            return DemoAgentModel.create(templates);
        }
        var requestFactory = new JdkClientHttpRequestFactory(http);
        // Transport read timeout and total model-call timeout protect different stages of I/O.
        requestFactory.setReadTimeout(timeouts.llmCall());
        var api = OpenAiApi.builder()
                .baseUrl(endpoint.baseUrl().toString())
                .completionsPath(endpoint.completionsPath())
                .apiKey(settings.openaiApiKey())
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();
        var model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(settings.llmModel())
                        .maxTokens(runtime.maxOutputTokens())
                        .internalToolExecutionEnabled(false)
                        .build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .build();
        // Spring closes this AutoCloseable bean on shutdown, releasing its bounded worker pool.
        return new TimedAgentModel(SpringAiAgentModel.create(model, templates), timeouts.llmCall(), runtime.workers());
    }
}
