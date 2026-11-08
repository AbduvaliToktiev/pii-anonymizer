package ru.pii.anonymizer;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;

import ru.pii.anonymizer.pseudonym.Restorer;

/**
 * Коды меток зависят от случайного ключа, поэтому в тестах они перенумеровываются по порядку появления:
 * {@code [PERSON_K7M2QX9D] … [PERSON_3FQ8ZB1M] … [PERSON_K7M2QX9D]} → {@code [PERSON_1] … [PERSON_2] … [PERSON_1]}.
 * Одинаковые коды получают одинаковые номера — проверка «один человек — одна метка» сохраняется.
 */
public final class Tokens {

    private Tokens() {
    }

    /** Настоящая метка из {@code text}, которая при перенумерации станет {@code numbered} ({@code [PERSON_2]}). */
    public static String actual(String text, String numbered) {
        Matcher m = Restorer.TOKEN.matcher(text);
        while (m.find()) {
            if (renumber(text.substring(0, m.end())).endsWith(numbered)) {
                return m.group();
            }
        }
        throw new AssertionError("В тексте нет метки, соответствующей " + numbered);
    }

    /** Матчер для jsonPath: строка после перенумерации равна {@code expected}. */
    public static org.hamcrest.Matcher<String> renumberedEqualTo(String expected) {
        return new org.hamcrest.FeatureMatcher<>(org.hamcrest.Matchers.equalTo(expected), "после перенумерации", "текст") {
            @Override
            protected String featureValueOf(String actual) {
                return renumber(actual);
            }
        };
    }

    public static String renumber(String text) {
        Map<String, String> numbers = new HashMap<>();
        Map<String, Integer> next = new HashMap<>();
        Matcher m = Restorer.TOKEN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String token = m.group();
            String numbered = numbers.computeIfAbsent(token, t -> {
                String body = t.substring(1, t.length() - 1);
                String type = body.substring(0, body.indexOf('_'));
                int age = body.indexOf("_AGE");
                String suffix = age < 0 ? "" : body.substring(age);
                return "[" + type + "_" + next.merge(type, 1, Integer::sum) + suffix + "]";
            });
            m.appendReplacement(sb, Matcher.quoteReplacement(numbered));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
