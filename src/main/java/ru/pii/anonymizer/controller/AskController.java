package ru.pii.anonymizer.controller;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import ru.pii.anonymizer.dto.AskResponse;
import ru.pii.anonymizer.dto.AskResult;
import ru.pii.anonymizer.service.AskService;

/**
 * Вопрос к LLM через PII-шлюз: файл и вопрос обезличиваются, ответ модели
 * возвращается с восстановленными персональными данными.
 */
@RestController
public class AskController {

    private final AskService askService;

    public AskController(AskService askService) {
        this.askService = askService;
    }

    @PostMapping(path = "/api/ask", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AskResponse ask(@RequestParam("prompt") String prompt,
                           @RequestParam(name = "file", required = false) MultipartFile file,
                           @RequestParam(name = "showAnonymized", defaultValue = "false") boolean showAnonymized)
            throws IOException {
        AskResult r = file == null
                ? askService.ask(prompt, null, null)
                : askService.ask(prompt, file.getOriginalFilename(), file.getBytes());

        return new AskResponse(r.answer(), r.model(), r.summary().unique(), r.restored(), r.unknownTokens(),
                r.inputTokens(), r.outputTokens(), r.truncated(),
                showAnonymized ? r.exchange().sentToLlm() : null,
                showAnonymized ? r.exchange().llmAnswer() : null);
    }
}
