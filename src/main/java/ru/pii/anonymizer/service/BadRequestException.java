package ru.pii.anonymizer.service;

/** Некорректные параметры запроса: контроллер отвечает 400. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
