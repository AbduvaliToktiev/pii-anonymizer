package ru.pii.anonymizer.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

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

import ru.pii.anonymizer.detect.PiiType;
import ru.pii.anonymizer.detect.TextAnonymizer;
import ru.pii.anonymizer.document.DocumentAnonymizer;
import ru.pii.anonymizer.document.UnsupportedDocumentException;
import ru.pii.anonymizer.pseudonym.AnonymizationSummary;
import ru.pii.anonymizer.pseudonym.PseudonymRegistry;
import ru.pii.anonymizer.pseudonym.PseudonymRegistryFactory;

@RestController
@RequestMapping("/api/anonymize")
public class AnonymizationController {

    /** Сколько разных значений каждого типа найдено, например {@code PERSON=2, SNILS=2}. */
    public static final String SUMMARY_HEADER = "X-Pii-Summary";
    /** Сколько всего фрагментов заменено. */
    public static final String TOTAL_HEADER = "X-Pii-Replacements";

    public record TextRequest(String text) {
    }

    public record TextResponse(String text, Map<PiiType, Integer> found, int replacements) {
    }

    private final DocumentAnonymizer documentAnonymizer;
    private final TextAnonymizer textAnonymizer;
    private final PseudonymRegistryFactory registries;

    public AnonymizationController(DocumentAnonymizer documentAnonymizer, TextAnonymizer textAnonymizer,
                                   PseudonymRegistryFactory registries) {
        this.registries = registries;
        this.documentAnonymizer = documentAnonymizer;
        this.textAnonymizer = textAnonymizer;
    }

    /** Принимает xlsx, xls, docx или pdf и возвращает обезличенный файл того же формата. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> anonymizeFile(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new UnsupportedDocumentException("Файл пустой");
        }
        DocumentAnonymizer.Result result = documentAnonymizer.anonymize(file.getOriginalFilename(), file.getBytes());

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.format().contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(result.fileName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header(SUMMARY_HEADER, result.summary().toHeaderValue())
                .header(TOTAL_HEADER, String.valueOf(result.summary().totalReplacements()))
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, SUMMARY_HEADER + ", " + TOTAL_HEADER)
                .body(result.content());
    }

    /** Обезличивание произвольного текста — удобно для быстрой проверки детекторов. */
    @PostMapping(path = "/text", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TextResponse anonymizeText(@RequestBody TextRequest request) {
        PseudonymRegistry registry = registries.create();
        String text = textAnonymizer.anonymize(request.text() == null ? "" : request.text(), registry);
        AnonymizationSummary summary = registry.summary();
        return new TextResponse(text, summary.unique(), summary.totalReplacements());
    }
}
