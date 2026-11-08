package ru.pii.anonymizer.gateway;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Что Claude Code прислал в последнем шаге диалога — для лога шлюза.
 *
 * <p>Claude Code каждый раз отправляет всю историю, а новое — в последнем сообщении пользователя:
 * результаты инструментов ({@code tool_result}) и вложенные документы. Для каждого результата ищется вызов
 * инструмента ({@code tool_use}) в предыдущем ответе модели: из него берётся путь к файлу (Read, Edit, Write,
 * NotebookEdit) или имя инструмента (Bash, Grep и т.п.).
 */
public final class RequestFiles {

    private static final List<String> PATH_FIELDS = List.of("file_path", "notebook_path", "path");

    private RequestFiles() {
    }

    /** Пути к файлам и имена инструментов из последнего шага; пусто, если это просто реплика пользователя. */
    public static List<String> latestTurn(ObjectNode request) {
        JsonNode messages = request.path("messages");
        if (!messages.isArray() || messages.isEmpty()) {
            return List.of();
        }
        JsonNode last = messages.get(messages.size() - 1);
        if (!"user".equals(last.path("role").asText())) {
            return List.of();
        }
        Map<String, JsonNode> calls = toolCalls(messages.size() >= 2 ? messages.get(messages.size() - 2) : null);

        List<String> result = new ArrayList<>();
        for (JsonNode block : last.path("content")) {
            switch (block.path("type").asText()) {
                case "tool_result" -> {
                    JsonNode call = calls.get(block.path("tool_use_id").asText());
                    if (call != null) {
                        result.add(describe(call));
                    }
                }
                case "document" -> result.add("документ " + block.path("title").asText("без названия"));
                case "image" -> result.add("картинка");
                default -> {
                }
            }
        }
        return result.stream().distinct().toList();
    }

    private static Map<String, JsonNode> toolCalls(JsonNode assistant) {
        Map<String, JsonNode> calls = new LinkedHashMap<>();
        if (assistant != null && "assistant".equals(assistant.path("role").asText())) {
            for (JsonNode block : assistant.path("content")) {
                if ("tool_use".equals(block.path("type").asText())) {
                    calls.put(block.path("id").asText(), block);
                }
            }
        }
        return calls;
    }

    private static String describe(JsonNode call) {
        JsonNode input = call.path("input");
        for (String field : PATH_FIELDS) {
            String path = input.path(field).asText("");
            if (!path.isBlank()) {
                return path;
            }
        }
        return call.path("name").asText("инструмент");
    }
}
