package ru.pii.anonymizer.gateway;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import ru.pii.anonymizer.pseudonym.PseudonymRegistry;
import ru.pii.anonymizer.pseudonym.Restorer;

/**
 * Восстановление настоящих данных в ответе модели — обычном JSON и потоковом (SSE).
 *
 * <p>Восстанавливается текст ответа и аргументы вызовов инструментов: если Claude правит файл
 * или запускает команду с меткой {@code [PERSON_1]}, на компьютер пользователя она придёт уже
 * с настоящим значением. Блоки размышлений не меняются — они подписаны моделью.
 */
final class ResponseRestorer {

    private final ObjectMapper json;
    private final PseudonymRegistry registry;

    ResponseRestorer(ObjectMapper json, PseudonymRegistry registry) {
        this.json = json;
        this.registry = registry;
    }

    /** Обычный (не потоковый) ответ Messages API. */
    byte[] restoreMessage(byte[] body) throws IOException {
        JsonNode root = json.readTree(body);
        if (root.path("content") instanceof ArrayNode blocks) {
            for (JsonNode block : blocks) {
                if (block instanceof ObjectNode object) {
                    switch (object.path("type").asText("")) {
                        case "text" -> {
                            if (object.path("text").isTextual()) {
                                object.put("text", restore(object.get("text").asText()));
                            }
                        }
                        case "tool_use" -> {
                            if (object.has("input")) {
                                object.set("input", restoreStrings(object.get("input")));
                            }
                        }
                        default -> {
                        }
                    }
                }
            }
        }
        return json.writeValueAsBytes(root);
    }

    /**
     * Потоковый ответ: события читаются по одному, кусочки текста и аргументов инструментов
     * восстанавливаются с учётом меток, разорванных между событиями, и сразу отдаются клиенту.
     */
    void restoreStream(InputStream upstream, OutputStream client) throws IOException {
        Map<Integer, StreamingRestorer> textBlocks = new HashMap<>();
        Map<Integer, StreamingRestorer> jsonBlocks = new HashMap<>();
        UnaryOperator<String> asJsonString = this::jsonEscape;

        BufferedReader reader = new BufferedReader(new InputStreamReader(upstream, StandardCharsets.UTF_8));
        String eventName = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("event:")) {
                eventName = line.substring(6).strip();
                continue;
            }
            if (!line.startsWith("data:")) {
                // пустая строка-разделитель, комментарии и keep-alive пинги — как есть
                write(client, line + "\n");
                if (line.isEmpty()) {
                    client.flush();
                }
                continue;
            }

            String data = line.substring(5).strip();
            JsonNode event;
            try {
                event = json.readTree(data);
            } catch (JsonProcessingException e) {
                writeEvent(client, eventName, data);
                continue;
            }
            String type = event.path("type").asText("");
            int index = event.path("index").asInt(-1);

            switch (type) {
                case "content_block_start" -> {
                    JsonNode block = event.path("content_block");
                    String blockType = block.path("type").asText("");
                    if ("text".equals(blockType)) {
                        textBlocks.put(index, new StreamingRestorer(registry::originalOf, UnaryOperator.identity()));
                        if (block instanceof ObjectNode object && object.path("text").isTextual()) {
                            object.put("text", restore(object.get("text").asText()));
                        }
                    } else if ("tool_use".equals(blockType) || "server_tool_use".equals(blockType)) {
                        jsonBlocks.put(index, new StreamingRestorer(registry::originalOf, asJsonString));
                        if (block instanceof ObjectNode object && object.has("input")) {
                            object.set("input", restoreStrings(object.get("input")));
                        }
                    }
                    writeEvent(client, eventName, json.writeValueAsString(event));
                }
                case "content_block_delta" -> {
                    JsonNode delta = event.path("delta");
                    if (delta instanceof ObjectNode d && "text_delta".equals(d.path("type").asText())
                            && textBlocks.containsKey(index)) {
                        d.put("text", textBlocks.get(index).push(d.path("text").asText("")));
                    } else if (delta instanceof ObjectNode d && "input_json_delta".equals(d.path("type").asText())
                            && jsonBlocks.containsKey(index)) {
                        d.put("partial_json", jsonBlocks.get(index).push(d.path("partial_json").asText("")));
                    }
                    writeEvent(client, eventName, json.writeValueAsString(event));
                }
                case "content_block_stop" -> {
                    flushBlock(client, index, textBlocks.remove(index), "text_delta", "text");
                    flushBlock(client, index, jsonBlocks.remove(index), "input_json_delta", "partial_json");
                    writeEvent(client, eventName, data);
                }
                default -> writeEvent(client, eventName, data);
            }
            eventName = null;
        }
        client.flush();
    }

    /** Остаток придержанного текста отправляется отдельным событием перед закрытием блока. */
    private void flushBlock(OutputStream client, int index, StreamingRestorer restorer, String deltaType,
                            String field) throws IOException {
        if (restorer == null) {
            return;
        }
        String rest = restorer.flush();
        if (rest.isEmpty()) {
            return;
        }
        ObjectNode event = json.createObjectNode();
        event.put("type", "content_block_delta");
        event.put("index", index);
        ObjectNode delta = event.putObject("delta");
        delta.put("type", deltaType);
        delta.put(field, rest);
        writeEvent(client, "content_block_delta", json.writeValueAsString(event));
        write(client, "\n");
    }

    private String restore(String text) {
        return Restorer.restore(text, registry::originalOf).text();
    }

    private JsonNode restoreStrings(JsonNode node) {
        if (node.isTextual()) {
            return TextNode.valueOf(restore(node.asText()));
        }
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                field.setValue(restoreStrings(field.getValue()));
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                array.set(i, restoreStrings(array.get(i)));
            }
        }
        return node;
    }

    /** Значение для вставки внутрь JSON-строки: экранированы кавычки, слэши и управляющие символы. */
    private String jsonEscape(String value) {
        try {
            String quoted = json.writeValueAsString(value);
            return quoted.substring(1, quoted.length() - 1);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeEvent(OutputStream out, String eventName, String data) throws IOException {
        if (eventName != null) {
            write(out, "event: " + eventName + "\n");
        }
        write(out, "data: " + data + "\n");
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }
}
