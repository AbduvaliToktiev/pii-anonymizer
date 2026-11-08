package ru.pii.anonymizer.service;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ru.pii.anonymizer.detect.TextAnonymizer;
import ru.pii.anonymizer.document.DocumentAnonymizer;
import ru.pii.anonymizer.document.DocumentProcessingException;
import ru.pii.anonymizer.document.DocumentTextExtractor;
import ru.pii.anonymizer.dto.AskResult;
import ru.pii.anonymizer.dto.LlmExchange;
import ru.pii.anonymizer.dto.PreparedAsk;
import ru.pii.anonymizer.llm.LlmClient;
import ru.pii.anonymizer.llm.LlmReply;
import ru.pii.anonymizer.pseudonym.AnonymizationSummary;
import ru.pii.anonymizer.pseudonym.PseudonymRegistry;
import ru.pii.anonymizer.pseudonym.PseudonymRegistryFactory;
import ru.pii.anonymizer.pseudonym.Restorer;

/**
 * PII-шлюз: обезличивает файл и вопрос, отправляет в LLM, восстанавливает ответ.
 *
 * <p>Таблица соответствия «метка → значение» живёт только в памяти на время запроса
 * и после ответа удаляется. Наружу уходят только метки; в лог — только статистика.
 */
@Service
public class AskService {

    private static final Logger log = LoggerFactory.getLogger(AskService.class);

    static final String SYSTEM_PROMPT = """
            Ты помогаешь работать с документами. Персональные данные в документе и в вопросе \
            заменены зашифрованными метками: тип и код, например [PERSON_K7M2QX9D], [SNILS_3FQ8ZB1M], [INN_…], \
            [PASSPORT_…], [PHONE_…], [EMAIL_…], [CARD_…], [BIRTHDATE_…], [ADDRESS_…]. \
            Одна метка всегда обозначает одно и то же значение, в том числе человека в любом падеже: \
            строки с одинаковой меткой относятся к одному человеку — по ним можно считать суммы, количество и группировки. \
            Если в метке даты рождения есть хвост _AGE с числом, это возраст человека в полных годах на сегодня.
            Правила:
            - Используй метки в ответе дословно, символ в символ: не склоняй, не сокращай, не меняй регистр и код — \
            после ответа они будут автоматически заменены настоящими данными.
            - Не пытайся угадать, что скрыто за меткой, и не придумывай новых меток.
            - Числа, суммы, даты (кроме дат рождения), отделы и должности не обезличены — используй их для расчётов.
            Отвечай на языке вопроса.""";

    private final DocumentAnonymizer documentAnonymizer;
    private final DocumentTextExtractor extractor;
    private final TextAnonymizer textAnonymizer;
    private final LlmClient llm;
    private final PseudonymRegistryFactory registries;

    public AskService(DocumentAnonymizer documentAnonymizer, DocumentTextExtractor extractor,
                      TextAnonymizer textAnonymizer, LlmClient llm, PseudonymRegistryFactory registries) {
        this.documentAnonymizer = documentAnonymizer;
        this.extractor = extractor;
        this.textAnonymizer = textAnonymizer;
        this.llm = llm;
        this.registries = registries;
    }

    public boolean llmConfigured() {
        return llm.isConfigured();
    }

    /**
     * @param fileName имя файла или {@code null}, если вопрос без файла
     * @param content  содержимое файла или {@code null}
     */
    public AskResult ask(String prompt, String fileName, byte[] content) {
        if (prompt == null || prompt.isBlank()) {
            throw new BadRequestException("Пустой вопрос: передайте текст в поле prompt");
        }
        return send(prepare(prompt, fileName, content == null || content.length == 0 ? null : content));
    }

    /** Обезличивает файл и вопрос, ничего не отправляя наружу. */
    public PreparedAsk prepare(String prompt, String fileName, byte[] content) {
        PseudonymRegistry registry = registries.create();

        StringBuilder message = new StringBuilder();
        if (content != null) {
            DocumentAnonymizer.Result document = documentAnonymizer.anonymize(fileName, content, registry);
            String text;
            try {
                text = extractor.toText(document.format(), document.content());
            } catch (IOException e) {
                throw new DocumentProcessingException("Не удалось извлечь текст из обезличенного файла", e);
            }
            // Имя файла не передаём: в нём тоже бывают фамилии
            message.append("<document format=\"").append(document.format().extension()).append("\">\n")
                    .append(text)
                    .append("\n</document>\n\n");
        }
        String anonymizedPrompt = textAnonymizer.anonymize(prompt, registry);
        message.append(anonymizedPrompt);
        return new PreparedAsk(message.toString(), anonymizedPrompt, registry);
    }

    /** Отправляет обезличенный запрос в модель и восстанавливает ответ. */
    public AskResult send(PreparedAsk prepared) {
        PseudonymRegistry registry = prepared.registry();
        String sent = prepared.message();
        LlmReply reply = llm.ask(SYSTEM_PROMPT, sent);
        Restorer.Result restored = Restorer.restore(reply.text(), registry.mapping());

        AnonymizationSummary summary = registry.summary();
        log.info("Запрос к {}: найдено [{}], токены {}/{}, восстановлено меток {}, неизвестных {}",
                reply.model(), summary.toHeaderValue(), reply.inputTokens(), reply.outputTokens(),
                restored.restored(), restored.unknown().size());

        return new AskResult(restored.text(), reply.model(), summary, restored.restored(), restored.unknown(),
                reply.inputTokens(), reply.outputTokens(), reply.truncated(), new LlmExchange(sent, reply.text()));
    }
}
