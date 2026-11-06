package ru.pii.anonymizer.web;

/** Некорректные параметры запроса. */
class BadRequestException extends RuntimeException {

    BadRequestException(String message) {
        super(message);
    }
}
