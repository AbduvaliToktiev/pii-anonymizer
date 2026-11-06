package ru.pii.anonymizer.web;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.annotation.JsonInclude;

import ru.pii.anonymizer.ask.AskService;
import ru.pii.anonymizer.detect.PiiType;

/**
 * Вопрос к LLM через PII-шлюз: файл и вопрос обезличиваются, ответ модели
 * возвращается с восстановленными персональными данными.
 */
@RestController
public class AskController {

    /**
     * @param answer        ответ модели с подставленными настоящими данными
     * @param found         сколько разных значений каждого типа обезличено
     * @param unknownTokens метки из ответа, которых не было в запросе (оставлены как есть)
     * @param sentToLlm     что увидела модель — только при {@code showAnonymized=true}
     * @param llmAnswer     ответ модели до восстановления — только при {@code showAnonymized=true}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AskResponse(String answer, String model, Map<PiiType, Integer> found, int restored,
                              Set<String> unknownTokens, long inputTokens, long outputTokens, boolean truncated,
                              String sentToLlm, String llmAnswer) {
    }

    private final AskService askService;

    public AskController(AskService askService) {
        this.askService = askService;
    }

    @PostMapping(path = "/api/ask", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AskResponse ask(@RequestParam("prompt") String prompt,
                           @RequestParam(name = "file", required = false) MultipartFile file,
                           @RequestParam(name = "showAnonymized", defaultValue = "false") boolean showAnonymized)
            throws IOException {
        if (prompt.isBlank()) {
            throw new BadRequestException("Пустой вопрос: передайте текст в поле prompt");
        }
        boolean hasFile = file != null && !file.isEmpty();
        AskService.Result r = askService.ask(prompt,
                hasFile ? file.getOriginalFilename() : null,
                hasFile ? file.getBytes() : null);

        return new AskResponse(r.answer(), r.model(), r.summary().unique(), r.restored(), r.unknownTokens(),
                r.inputTokens(), r.outputTokens(), r.truncated(),
                showAnonymized ? r.exchange().sentToLlm() : null,
                showAnonymized ? r.exchange().llmAnswer() : null);
    }
}
