package ru.pii.anonymizer.detect;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Детектор на основе регулярного выражения с необязательной дополнительной проверкой
 * найденного значения (например, контрольной суммы).
 *
 * @param group номер группы регулярного выражения, которая считается ПДн (0 — всё совпадение)
 */
public record RegexPiiDetector(PiiType type, Pattern pattern, int group, Predicate<String> validator)
        implements PiiDetector {

    public static RegexPiiDetector of(PiiType type, String regex) {
        return new RegexPiiDetector(type, compile(regex), 0, _ -> true);
    }

    public static RegexPiiDetector of(PiiType type, String regex, Predicate<String> validator) {
        return new RegexPiiDetector(type, compile(regex), 0, validator);
    }

    public static RegexPiiDetector ofGroup(PiiType type, String regex, int group, Predicate<String> validator) {
        return new RegexPiiDetector(type, compile(regex), group, validator);
    }

    @Override
    public List<Finding> detect(String text) {
        List<Finding> findings = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            String value = m.group(group);
            if (value != null && !value.isEmpty() && validator.test(value)) {
                findings.add(new Finding(type, m.start(group), m.end(group), value));
            }
        }
        return findings;
    }

    private static Pattern compile(String regex) {
        // UNICODE_CHARACTER_CLASS: \w, \s и границы корректно работают с кириллицей
        return Pattern.compile(regex, Pattern.UNICODE_CHARACTER_CLASS);
    }
}
