package ru.pii.anonymizer.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import ru.pii.anonymizer.document.DocumentProcessingException;
import ru.pii.anonymizer.document.UnsupportedDocumentException;
import ru.pii.anonymizer.llm.LlmException;

/**
 * Ошибки в формате RFC 9457 (Problem Details). Тексты исключений формируются нами
 * и не содержат содержимого файла, поэтому их можно отдавать клиенту.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(UnsupportedDocumentException.class)
    ProblemDetail unsupported(UnsupportedDocumentException e) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Неподдерживаемый файл", e.getMessage());
    }

    @ExceptionHandler(DocumentProcessingException.class)
    ProblemDetail processingFailed(DocumentProcessingException e) {
        log.warn("Не удалось обработать файл: {}", e.getMessage());
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Файл не удалось обработать", e.getMessage());
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ProblemDetail missingFile(MissingServletRequestPartException e) {
        return problem(HttpStatus.BAD_REQUEST, "Нет файла", "Передайте файл в поле формы «file»");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ProblemDetail missingParameter(MissingServletRequestParameterException e) {
        return problem(HttpStatus.BAD_REQUEST, "Нет параметра", "Передайте поле формы «" + e.getParameterName() + "»");
    }

    @ExceptionHandler(BadRequestException.class)
    ProblemDetail badRequest(BadRequestException e) {
        return problem(HttpStatus.BAD_REQUEST, "Некорректный запрос", e.getMessage());
    }

    @ExceptionHandler(LlmException.class)
    ProblemDetail llmFailed(LlmException e) {
        log.warn("Ошибка обращения к LLM: {}", e.getMessage());
        return problem(HttpStatus.BAD_GATEWAY, "LLM недоступна", e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail tooLarge(MaxUploadSizeExceededException e) {
        return problem(HttpStatus.CONTENT_TOO_LARGE, "Файл слишком большой", "Максимальный размер файла — 20 МБ");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
