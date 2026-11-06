package ru.pii.anonymizer.detect;

/**
 * Типы персональных данных, которые умеет находить сервис.
 * Порядок объявления задаёт приоритет при пересечении находок одинаковой длины.
 * Имя типа — часть метки ({@code [BIRTHDATE_1]}), поэтому только латинские буквы без «_».
 */
public enum PiiType {
    EMAIL,
    CARD,
    SNILS,
    INN,
    PASSPORT,
    PHONE,
    BIRTHDATE,
    ADDRESS,
    PERSON
}
