package ru.pii.anonymizer.detect;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Идентификаторы, записанные числом с плавающей точкой:
 * <ul>
 *   <li>в научной нотации — так Excel хранит СНИЛС, ИНН и номера карт внутри файла
 *       ({@code <v>1.2345678964E10</v>}), и так их показывают скрипты, читающие xlsx как XML;</li>
 *   <li>с нулевой дробной частью — так Python печатает число из ячейки Excel
 *       ({@code 7707083893.0}); без этого метка заменила бы цифры, а «.0» осталось бы снаружи.</li>
 * </ul>
 * Число разворачивается в цифры и проверяется контрольными суммами, как обычный идентификатор.
 */
public final class ScientificNumberDetector implements PiiDetector {

    private static final Pattern SCIENTIFIC = Pattern.compile(
            "(?<![\\w.])(?:\\d\\.\\d{1,18}[Ee]\\+?\\d{1,2}|\\d{10,19}\\.0+)(?![\\w.])");

    @Override
    public List<Finding> detect(String text) {
        List<Finding> findings = new ArrayList<>();
        Matcher m = SCIENTIFIC.matcher(text);
        while (m.find()) {
            PiiType type = classify(m.group());
            if (type != null) {
                findings.add(new Finding(type, m.start(), m.end(), m.group()));
            }
        }
        return findings;
    }

    private static PiiType classify(String value) {
        String digits;
        try {
            BigDecimal number = new BigDecimal(value);
            if (number.stripTrailingZeros().scale() > 0) {
                return null;
            }
            digits = number.toBigInteger().toString();
        } catch (NumberFormatException e) {
            return null;
        }
        if (Checksums.isValidSnils(digits)) {
            return PiiType.SNILS;
        }
        if (Checksums.isValidInn(digits)) {
            return PiiType.INN;
        }
        if (Checksums.isValidCard(digits)) {
            return PiiType.CARD;
        }
        if (digits.length() == 11 && (digits.startsWith("7") || digits.startsWith("8"))) {
            return PiiType.PHONE;
        }
        return null;
    }
}
