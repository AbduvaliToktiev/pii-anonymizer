package ru.pii.anonymizer.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ru.pii.anonymizer.SampleDocuments;

/**
 * Полный путь Claude Code → шлюз → «Anthropic» (локальная заглушка) → шлюз → Claude Code.
 * Заглушка запоминает, что ей прислали, и отвечает метками, разорванными между событиями потока.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayIntegrationTest {

    private static final HttpServer UPSTREAM;
    private static final AtomicReference<String> RECEIVED_BODY = new AtomicReference<>();
    private static final AtomicReference<String> RECEIVED_AUTH = new AtomicReference<>();
    private static final AtomicReference<String> RECEIVED_BETA = new AtomicReference<>();
    private static final AtomicReference<String> RECEIVED_PATH = new AtomicReference<>();

    static {
        try {
            UPSTREAM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        UPSTREAM.createContext("/", GatewayIntegrationTest::fakeAnthropic);
        UPSTREAM.start();
    }

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) {
        registry.add("anonymizer.gateway.upstream-url", () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort());
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop(0);
    }

    @Value("${local.server.port}")
    private int port;

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void reset() {
        RECEIVED_BODY.set(null);
    }

    /** Запрос в форме, как его отправляет Claude Code: файл прочитан инструментом Read. */
    private String claudeCodeRequest(boolean stream) {
        return """
                {"model":"claude-opus-5-5","max_tokens":1024,"stream":%s,
                 "system":[{"type":"text","text":"You are Claude Code. Пользователь: Иванов Иван Иванович"}],
                 "messages":[
                  {"role":"user","content":[{"type":"text","text":"Посчитай зарплаты в файле staff.csv"}]},
                  {"role":"assistant","content":[
                    {"type":"thinking","thinking":"","signature":"sig-abc"},
                    {"type":"tool_use","id":"toolu_1","name":"Read","input":{"file_path":"staff.csv"}}]},
                  {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":
                    "ФИО;Телефон;Зарплата\\nИванов Иван Иванович;8-916-123-45-67;120000\\nПетрова Анна Сергеевна;+7 903 765-43-21;250000"},
                    {"type":"image","source":{"type":"base64","media_type":"image/png","data":"iVBORw0KGgo="}}]}
                 ]}""".formatted(stream);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer oauth-token-from-subscription")
                        .header("anthropic-version", "2023-06-01")
                        .header("anthropic-beta", "oauth-2025-04-20")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void anthropicSeesOnlyTokensAndClaudeCodeGetsRealData() throws Exception {
        HttpResponse<String> response = post("/v1/messages?beta=true", claudeCodeRequest(true));

        // Что ушло в Anthropic
        String sent = RECEIVED_BODY.get();
        assertThat(RECEIVED_PATH.get()).isEqualTo("/v1/messages?beta=true");
        assertThat(sent).doesNotContain("Иванов", "Петрова", "916-123", "765-43-21");
        assertThat(sent).contains("[PERSON_", "[PHONE_", "120000", "250000", "sig-abc", "staff.csv");
        assertThat(sent).doesNotContain("iVBORw0KGgo=").contains(MessagesAnonymizer.IMAGE_REMOVED);
        assertThat(RECEIVED_AUTH.get()).isEqualTo("Bearer oauth-token-from-subscription");
        assertThat(RECEIVED_BETA.get()).isEqualTo("oauth-2025-04-20");

        // Что получил Claude Code: склеиваем текст и аргументы инструмента из потока
        assertThat(response.statusCode()).isEqualTo(200);
        StringBuilder text = new StringBuilder();
        StringBuilder toolInput = new StringBuilder();
        for (String line : response.body().split("\n")) {
            if (line.startsWith("data: ")) {
                JsonNode event = json.readTree(line.substring(6));
                JsonNode delta = event.path("delta");
                text.append(delta.path("text").asText(""));
                toolInput.append(delta.path("partial_json").asText(""));
            }
        }
        assertThat(text.toString()).isEqualTo("Больше всех получает Петрова Анна Сергеевна (тел. +7 903 765-43-21).");
        JsonNode input = json.readTree(toolInput.toString());
        assertThat(input.path("content").asText())
                .isEqualTo("Иванов Иван Иванович;8-916-123-45-67;premium");
    }

    @Test
    void nonStreamingResponseIsRestored() throws Exception {
        HttpResponse<String> response = post("/v1/messages", claudeCodeRequest(false));

        JsonNode body = json.readTree(response.body());
        assertThat(body.path("content").get(0).path("text").asText())
                .isEqualTo("Больше всех получает Петрова Анна Сергеевна (тел. +7 903 765-43-21).");
        assertThat(body.path("content").get(1).path("input").path("content").asText())
                .isEqualTo("Иванов Иван Иванович;8-916-123-45-67;premium");
    }

    @Test
    void sameValueGetsSameTokenAcrossRequests() throws Exception {
        post("/v1/messages", claudeCodeRequest(false));
        String first = RECEIVED_BODY.get();
        post("/v1/messages", claudeCodeRequest(false));

        assertThat(RECEIVED_BODY.get()).isEqualTo(first);
    }

    @Test
    void pdfDocumentIsSentAsAnonymizedText() throws Exception {
        var font = SampleDocuments.systemFont();
        org.junit.jupiter.api.Assumptions.assumeTrue(font.isPresent());
        String pdf = Base64.getEncoder().encodeToString(SampleDocuments.memoPdf(font.get()));

        post("/v1/messages", """
                {"model":"claude-opus-5-5","max_tokens":100,"messages":[{"role":"user","content":[
                 {"type":"document","source":{"type":"base64","media_type":"application/pdf","data":"%s"}},
                 {"type":"text","text":"Кратко перескажи"}]}]}""".formatted(pdf));

        String sent = RECEIVED_BODY.get();
        assertThat(sent).doesNotContain(pdf.substring(0, 50), "Петровой", "765-43-21");
        assertThat(sent).contains("<document>", "Служебная записка", "[PERSON_");
    }

    @Test
    void otherEndpointsArePassedThrough() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/v1/models")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"data\":[]}");
    }

    // ---------- заглушка Anthropic ----------

    private static void fakeAnthropic(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = ex.getRequestURI().toString();
        if (path.startsWith("/v1/models")) {
            reply(ex, 200, "application/json", "{\"data\":[]}");
            return;
        }
        RECEIVED_BODY.set(body);
        RECEIVED_PATH.set(path);
        RECEIVED_AUTH.set(ex.getRequestHeaders().getFirst("Authorization"));
        RECEIVED_BETA.set(ex.getRequestHeaders().getFirst("anthropic-beta"));

        // Метки из присланного файла: кто на какой строке
        String ivanov = tokenAfterLine(body, 1, 0);
        String petrova = tokenAfterLine(body, 2, 0);
        String ivanovPhone = tokenAfterLine(body, 1, 1);
        String petrovaPhone = tokenAfterLine(body, 2, 1);

        String text = "Больше всех получает " + petrova + " (тел. " + petrovaPhone + ").";
        String toolJson = "{\"file_path\":\"result.csv\",\"content\":\"" + ivanov + ";" + ivanovPhone + ";premium\"}";

        if (body.contains("\"stream\":true")) {
            int cutText = text.indexOf("[PERSON_") + 4;
            int cutJson = toolJson.indexOf("[PHONE_") + 3;
            String sse = event("message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"content\":[]}}")
                    + event("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")
                    + event("ping", "{\"type\":\"ping\"}")
                    + delta(0, "text_delta", "text", text.substring(0, cutText))
                    + delta(0, "text_delta", "text", text.substring(cutText))
                    + event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
                    + event("content_block_start", "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_2\",\"name\":\"Write\",\"input\":{}}}")
                    + delta(1, "input_json_delta", "partial_json", toolJson.substring(0, cutJson))
                    + delta(1, "input_json_delta", "partial_json", toolJson.substring(cutJson))
                    + event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}")
                    + event("message_stop", "{\"type\":\"message_stop\"}");
            reply(ex, 200, "text/event-stream", sse);
        } else {
            reply(ex, 200, "application/json", "{\"id\":\"msg_1\",\"type\":\"message\",\"content\":["
                    + "{\"type\":\"text\",\"text\":" + quote(text) + "},"
                    + "{\"type\":\"tool_use\",\"id\":\"toolu_2\",\"name\":\"Write\",\"input\":" + toolJson + "}]}");
        }
    }

    /** Метка в строке файла: line — номер строки данных (1 или 2), column — 0 для ФИО, 1 для телефона. */
    private static String tokenAfterLine(String body, int line, int column) {
        Matcher m = Pattern.compile("(\\[PERSON_\\d+]);(\\[PHONE_\\d+]);(\\d+)").matcher(body);
        for (int i = 0; i < line; i++) {
            if (!m.find()) {
                return "?";
            }
        }
        return m.group(column + 1);
    }

    private static String event(String name, String data) {
        return "event: " + name + "\ndata: " + data + "\n\n";
    }

    private static String delta(int index, String type, String field, String value) {
        return event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":" + index
                + ",\"delta\":{\"type\":\"" + type + "\",\"" + field + "\":" + quote(value) + "}}");
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void reply(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
