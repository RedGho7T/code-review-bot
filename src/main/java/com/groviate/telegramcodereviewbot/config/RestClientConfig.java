package com.groviate.telegramcodereviewbot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Bean
    public RestTemplate restTemplate(
            RestTemplateBuilder builder,
            @Value("${gitlab.api.token:}") String gitlabToken
    ) {
        ClientHttpRequestInterceptor auth = (request, body, execution) -> {
            if (gitlabToken != null && !gitlabToken.isBlank()) {
                request.getHeaders().add("Private-Token", gitlabToken);
            }
            return execution.execute(request, body);
        };

        return builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(120))
                .additionalInterceptors(auth)
                .build();
    }
}