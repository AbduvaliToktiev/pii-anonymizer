package ru.pii.anonymizer.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import ru.pii.anonymizer.document.DocumentAnonymizer;
import ru.pii.anonymizer.dto.TextRequest;
import ru.pii.anonymizer.dto.TextResponse;
import ru.pii.anonymizer.dto.TextResult;
import ru.pii.anonymizer.service.AnonymizationService;

/** Обезличивание без модели: файл ({@code POST /api/anonymize}) и текст ({@code POST /api/anonymize/text}). */
@RestController
@RequestMapping("/api/anonymize")
public class AnonymizationController {

    /** Сколько разных значений каждого типа найдено, например {@code PERSON=2, SNILS=2}. */
    public static final String SUMMARY_HEADER = "X-Pii-Summary";
    /** Сколько всего фрагментов заменено. */
    public static final String TOTAL_HEADER = "X-Pii-Replacements";

    private final AnonymizationService service;

    public AnonymizationController(AnonymizationService service) {
        this.service = service;
    }

    /** Принимает xlsx, xls, docx, doc, pptx, odt или pdf и возвращает обезличенный файл того же формата. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> anonymizeFile(@RequestParam("file") MultipartFile file) throws IOException {
        DocumentAnonymizer.Result document = service.anonymizeFile(file.getOriginalFilename(), file.getBytes());

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.format().contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(document.fileName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header(SUMMARY_HEADER, document.summary().toHeaderValue())
                .header(TOTAL_HEADER, String.valueOf(document.summary().totalReplacements()))
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, SUMMARY_HEADER + ", " + TOTAL_HEADER)
                .body(document.content());
    }

    /** Обезличивание произвольного текста — удобно для быстрой проверки детекторов. */
    @PostMapping(path = "/text", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TextResponse anonymizeText(@RequestBody TextRequest request) {
        TextResult result = service.anonymizeText(request.text());
        return new TextResponse(result.text(), result.summary().unique(), result.summary().totalReplacements());
    }
}
