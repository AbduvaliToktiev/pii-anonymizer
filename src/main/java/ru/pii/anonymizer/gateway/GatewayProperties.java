package ru.pii.anonymizer.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки шлюза для Claude Code.
 *
 * @param upstreamUrl куда пересылать обезличенные запросы
 */
@ConfigurationProperties("anonymizer.gateway")
public record GatewayProperties(String upstreamUrl) {

    public GatewayProperties {
        if (upstreamUrl == null || upstreamUrl.isBlank()) {
            upstreamUrl = "https://api.anthropic.com";
        }
        upstreamUrl = upstreamUrl.replaceAll("/+$", "");
    }
}
