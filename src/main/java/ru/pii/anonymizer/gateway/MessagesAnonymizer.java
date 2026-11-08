package ru.pii.anonymizer.gateway;

import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import ru.pii.anonymizer.detect.TextAnonymizer;
import ru.pii.anonymizer.document.DocumentFormat;
import ru.pii.anonymizer.document.DocumentTextExtractor;
import ru.pii.anonymizer.pseudonym.PseudonymRegistry;

/**
 * Обезличивание тела запроса Messages API ({@code POST /v1/messages}) на месте.
 *
 * <ul>
 *   <li>Системный промпт, текст сообщений, результаты инструментов (содержимое прочитанных файлов,
 *       вывод команд) и аргументы прошлых вызовов инструментов — обезличиваются.</li>
 *   <li>Блоки размышлений ({@code thinking}, {@code redacted_thinking}) не трогаются: они подписаны
 *       моделью, любое изменение ломает диалог.</li>
 *   <li>PDF превращается в обезличенный текст. Картинки обезличить нельзя — они заменяются
 *       пометкой и наружу не уходят.</li>
 *   <li>Определения инструментов и служебные поля (идентификаторы, типы, подписи) не меняются.</li>
 * </ul>
 */
public final class MessagesAnonymizer {

    static final String IMAGE_REMOVED = "[Изображение не передано: PII-шлюз не умеет обезличивать картинки]";
    static final String DOCUMENT_REMOVED = "[Документ не передан: PII-шлюз не смог извлечь из него текст для обезличивания]";

    /** Поля, которые никогда не содержат пользовательских данных и не должны меняться. */
    private static final Set<String> TECHNICAL_FIELDS = Set.of(
            "type", "id", "tool_use_id", "name", "signature", "media_type", "cache_control", "role", "citations",
            "data", "encrypted_content", "encrypted_index");

    private final TextAnonymizer anonymizer;
    private final DocumentTextExtractor extractor;
    private final PseudonymRegistry registry;

    public MessagesAnonymizer(TextAnonymizer anonymizer, DocumentTextExtractor extractor, PseudonymRegistry registry) {
        this.anonymizer = anonymizer;
        this.extractor = extractor;
        this.registry = registry;
    }

    public void anonymize(ObjectNode request) {
        JsonNode system = request.get("system");
        if (system != null) {
            request.set("system", anonymizeContent(system));
        }
        JsonNode messages = request.get("messages");
        if (messages instanceof ArrayNode array) {
            for (JsonNode message : array) {
                if (message instanceof ObjectNode object && object.has("content")) {
                    object.set("content", anonymizeContent(object.get("content")));
                }
            }
        }
    }

    /** Содержимое — строка или массив блоков. */
    private JsonNode anonymizeContent(JsonNode content) {
        if (content.isTextual()) {
            return text(content.asText());
        }
        if (content instanceof ArrayNode blocks) {
            for (int i = 0; i < blocks.size(); i++) {
                blocks.set(i, anonymizeBlock(blocks.get(i)));
            }
        }
        return content;
    }

    private JsonNode anonymizeBlock(JsonNode node) {
        if (!(node instanceof ObjectNode block)) {
            return node;
        }
        String type = block.path("type").asText("");
        switch (type) {
            case "thinking", "redacted_thinking" -> {
                return block;
            }
            case "text" -> anonymizeField(block, "text");
            case "tool_result" -> {
                if (block.has("content")) {
                    block.set("content", anonymizeContent(block.get("content")));
                }
            }
            case "tool_use", "server_tool_use" -> {
                if (block.has("input")) {
                    block.set("input", anonymizeStrings(block.get("input")));
                }
            }
            case "image" -> {
                return textBlock(IMAGE_REMOVED, block);
            }
            case "document" -> {
                return documentAsText(block);
            }
            default -> anonymizeStrings(block);
        }
        return block;
    }

    /** PDF или текстовый документ → текстовый блок с обезличенным содержимым. */
    private JsonNode documentAsText(ObjectNode block) {
        JsonNode source = block.path("source");
        String sourceType = source.path("type").asText("");
        String mediaType = source.path("media_type").asText("");
        String text = null;
        try {
            if ("base64".equals(sourceType) && "application/pdf".equals(mediaType)) {
                byte[] pdf = Base64.getMimeDecoder().decode(source.path("data").asText(""));
                text = extractor.toText(DocumentFormat.PDF, pdf);
            } else if ("text".equals(sourceType)) {
                text = source.path("data").asText("");
            }
        } catch (Exception e) {
            text = null;
        }
        if (text == null || text.isBlank()) {
            return textBlock(DOCUMENT_REMOVED, block);
        }
        String title = block.path("title").asText("");
        String header = title.isBlank() ? "<document>\n" : "<document title=\"" + anonymizer.anonymize(title, registry) + "\">\n";
        return textBlock(header + anonymizer.anonymize(text, registry) + "\n</document>", block);
    }

    /** Рекурсивно обезличивает все строковые значения, кроме служебных полей. */
    private JsonNode anonymizeStrings(JsonNode node) {
        if (node.isTextual()) {
            return text(node.asText());
        }
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!TECHNICAL_FIELDS.contains(field.getKey())) {
                    field.setValue(anonymizeStrings(field.getValue()));
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                array.set(i, anonymizeStrings(array.get(i)));
            }
        }
        return node;
    }

    private void anonymizeField(ObjectNode block, String field) {
        JsonNode value = block.get(field);
        if (value != null && value.isTextual()) {
            block.set(field, text(value.asText()));
        }
    }

    private TextNode text(String value) {
        return TextNode.valueOf(anonymizer.anonymize(value, registry));
    }

    /** Текстовый блок вместо исходного, с сохранением cache_control (влияет на кэширование промпта). */
    private static ObjectNode textBlock(String text, ObjectNode original) {
        ObjectNode block = JsonNodeFactory.instance.objectNode();
        block.put("type", "text");
        block.put("text", text);
        if (original.has("cache_control")) {
            block.set("cache_control", original.get("cache_control"));
        }
        return block;
    }
}
