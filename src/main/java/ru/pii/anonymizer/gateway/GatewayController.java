package ru.pii.anonymizer.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import ru.pii.anonymizer.detect.TextAnonymizer;
import ru.pii.anonymizer.document.DocumentTextExtractor;
import ru.pii.anonymizer.pseudonym.PseudonymRegistry;
import ru.pii.anonymizer.pseudonym.PseudonymRegistryFactory;

/**
 * PII-шлюз для Claude Code: {@code ANTHROPIC_BASE_URL=http://127.0.0.1:8080}.
 *
 * <p>Запросы к модели ({@code /v1/messages}, {@code /v1/messages/count_tokens}) обезличиваются
 * перед отправкой в Anthropic, ответы восстанавливаются перед возвратом в Claude Code.
 * Остальные запросы (метаданные, профиль) пересылаются без изменений: в них нет переписки.
 *
 * <p>Заголовки авторизации пересылаются как есть, поэтому работает и вход по подписке Claude,
 * и ключ API. Сам шлюз ключей не хранит и в лог их не пишет.
 *
 * <p>Один реестр меток на всё время работы шлюза: Claude Code каждый раз отправляет всю историю
 * диалога, и одно и то же значение должно получать одну и ту же метку во всех запросах — иначе
 * модель запутается, а кэширование промпта перестанет работать.
 */
@RestController
public class GatewayController {

    private static final Logger log = LoggerFactory.getLogger(GatewayController.class);

    /** Заголовки соединения, которые не пересылаются; accept-encoding убран, чтобы ответ пришёл без сжатия. */
    private static final Set<String> SKIPPED_HEADERS = Set.of("host", "content-length", "connection", "keep-alive",
            "transfer-encoding", "upgrade", "http2-settings", "te", "trailer", "proxy-connection", "expect",
            "accept-encoding");

    private final GatewayProperties properties;
    private final PseudonymRegistry registry;
    private final ObjectMapper json = new ObjectMapper();
    private final MessagesAnonymizer anonymizer;
    private final ResponseRestorer restorer;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public GatewayController(GatewayProperties properties, TextAnonymizer textAnonymizer,
                             DocumentTextExtractor extractor, PseudonymRegistryFactory registries) {
        this.properties = properties;
        this.registry = registries.create(true);
        this.anonymizer = new MessagesAnonymizer(textAnonymizer, extractor, registry);
        this.restorer = new ResponseRestorer(json, registry);
    }

    /** Всё, что не обработали собственные эндпоинты сервиса ({@code /api/anonymize}, {@code /api/ask}). */
    @RequestMapping("/**")
    public void proxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();
        boolean modelCall = "POST".equals(method)
                && ("/v1/messages".equals(path) || "/v1/messages/count_tokens".equals(path));

        byte[] body = request.getInputStream().readAllBytes();
        if (modelCall) {
            try {
                JsonNode root = json.readTree(body);
                if (!(root instanceof ObjectNode object)) {
                    throw new IllegalArgumentException("тело запроса не JSON-объект");
                }
                anonymizer.anonymize(object);
                body = json.writeValueAsBytes(object);
            } catch (JsonProcessingException | IllegalArgumentException e) {
                // Не смогли обезличить — не отправляем вовсе
                sendError(response, 400, "PII-шлюз не смог разобрать запрос, он не отправлен в Anthropic");
                return;
            }
        }

        String query = request.getQueryString();
        HttpRequest.Builder upstream = HttpRequest.newBuilder(
                        URI.create(properties.upstreamUrl() + path + (query == null ? "" : "?" + query)))
                .method(method, body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        for (String name : Collections.list(request.getHeaderNames())) {
            if (!SKIPPED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                for (String value : Collections.list(request.getHeaders(name))) {
                    upstream.header(name, value);
                }
            }
        }

        HttpResponse<InputStream> answer;
        try {
            answer = http.send(upstream.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendError(response, 502, "PII-шлюз: запрос к Anthropic прерван");
            return;
        } catch (IOException e) {
            log.warn("Шлюз: нет соединения с {}: {}", properties.upstreamUrl(), e.getClass().getSimpleName());
            sendError(response, 502, "PII-шлюз не смог подключиться к " + properties.upstreamUrl()
                    + ": " + e.getClass().getSimpleName());
            return;
        }

        String contentType = answer.headers().firstValue("content-type").orElse("");
        boolean stream = contentType.startsWith("text/event-stream");
        boolean restoreJson = modelCall && "/v1/messages".equals(path) && answer.statusCode() == 200
                && contentType.contains("json");

        response.setStatus(answer.statusCode());
        answer.headers().map().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!SKIPPED_HEADERS.contains(lower) && !lower.equals(":status")) {
                values.forEach(value -> response.addHeader(name, value));
            }
        });

        try (InputStream in = answer.body()) {
            OutputStream out = response.getOutputStream();
            if (modelCall && stream) {
                restorer.restoreStream(in, out);
            } else if (restoreJson) {
                out.write(restorer.restoreMessage(in.readAllBytes()));
            } else {
                in.transferTo(out);
            }
            out.flush();
        }
        if (modelCall) {
            log.info("Шлюз: {} {} → {}, меток в реестре: {}", method, path, answer.statusCode(),
                    registry.summary().unique().values().stream().mapToInt(Integer::intValue).sum());
        }
    }

    /** Ошибка в формате Anthropic API, чтобы Claude Code показал её текст. */
    private void sendError(HttpServletResponse response, int status, String message) throws IOException {
        ObjectNode error = json.createObjectNode();
        error.put("type", "error");
        ObjectNode details = error.putObject("error");
        details.put("type", status == 400 ? "invalid_request_error" : "api_error");
        details.put("message", message);
        response.setStatus(status);
        response.setContentType("application/json");
        response.getOutputStream().write(json.writeValueAsString(error).getBytes(StandardCharsets.UTF_8));
    }
}
