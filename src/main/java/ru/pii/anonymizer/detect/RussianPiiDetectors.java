package ru.pii.anonymizer.detect;

import java.util.List;

/**
 * Набор детекторов для российских персональных данных.
 * Числовые шаблоны используют {@code (?<!\d)} и {@code (?!\d)} вместо {@code \b},
 * чтобы находить значения, приклеенные к буквам («ИНН7707083893»).
 */
public final class RussianPiiDetectors {

    private RussianPiiDetectors() {
    }

    public static List<PiiDetector> all() {
        return List.of(
                email(), card(), snils(), inn(), passport(), passportWithKeyword(), phone(),
                new PersonNameDetector(), new CapitalizedNameDetector(),
                new BirthDateDetector(), new AddressDetector(), new TableColumnDetector(),
                new ScientificNumberDetector());
    }

    static PiiDetector email() {
        return RegexPiiDetector.of(PiiType.EMAIL,
                "(?<![\\w.+-])[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)*\\.\\p{L}{2,}");
    }

    static PiiDetector card() {
        return RegexPiiDetector.of(PiiType.CARD,
                "(?<!\\d)\\d{4}(?:[ -]?\\d{4}){3}\\d{0,3}(?!\\d)",
                value -> Checksums.isValidCard(Checksums.digitsOnly(value)));
    }

    static PiiDetector snils() {
        return RegexPiiDetector.of(PiiType.SNILS,
                "(?<!\\d)\\d{3}[- ]?\\d{3}[- ]?\\d{3}[- ]?\\d{2}(?!\\d)",
                value -> Checksums.isValidSnils(Checksums.digitsOnly(value)));
    }

    static PiiDetector inn() {
        return RegexPiiDetector.of(PiiType.INN,
                "(?<!\\d)(?:\\d{12}|\\d{10})(?!\\d)",
                Checksums::isValidInn);
    }

    /** Паспорт РФ с разделителем между серией и номером: «45 06 123456», «4506 № 123456». */
    static PiiDetector passport() {
        return RegexPiiDetector.of(PiiType.PASSPORT,
                "(?<!\\d)\\d{2}\\s?\\d{2}\\s+(?:№\\s*)?\\d{6}(?!\\d)");
    }

    /**
     * Паспорт, записанный слитно («паспорт 4506123456»). Без ключевого слова такие 10 цифр
     * неотличимы от ИНН, поэтому ищем только рядом со словом «паспорт».
     */
    static PiiDetector passportWithKeyword() {
        return RegexPiiDetector.ofGroup(PiiType.PASSPORT,
                "(?iu)паспорт\\D{0,30}?(?<!\\d)(\\d{10})(?!\\d)", 1, _ -> true);
    }

    static PiiDetector phone() {
        return RegexPiiDetector.of(PiiType.PHONE,
                "(?<![\\d+])(?:\\+7|8)[\\s(-]*\\d{3}[\\s)-]*\\d{3}[\\s-]*\\d{2}[\\s-]*\\d{2}(?!\\d)",
                value -> Checksums.digitsOnly(value).length() == 11);
    }
}
