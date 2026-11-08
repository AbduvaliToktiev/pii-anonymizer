package ru.pii.anonymizer.dto;

import java.util.List;
import java.util.Map;

/**
 * Входящий запрос Claude Code к шлюзу ({@code GatewayService.forward}), без привязки к сервлетам.
 *
 * @param method  HTTP-метод
 * @param path    путь, например {@code /v1/messages}
 * @param query   строка запроса после «?» или {@code null}
 * @param headers заголовки как есть, в том числе авторизация
 * @param body    тело запроса
 */
public record GatewayRequest(String method, String path, String query, Map<String, List<String>> headers, byte[] body) {
}
