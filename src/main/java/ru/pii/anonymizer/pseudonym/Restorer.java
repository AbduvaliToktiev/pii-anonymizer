package ru.pii.anonymizer.pseudonym;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ru.pii.anonymizer.detect.PiiType;

/**
 * Обратная подстановка: метки в ответе модели заменяются исходными значениями.
 * Работает только на стороне сервиса — таблица соответствия наружу не уходит.
 */
public final class Restorer {

    /**
     * Результат восстановления.
     *
     * @param restored сколько меток заменено
     * @param unknown  метки, которых нет в таблице: модель их выдумала или исказила — оставлены как есть
     */
    public record Result(String text, int restored, Set<String> unknown) {
    }

    /** Метка целиком: {@code [PERSON_12]}. */
    public static final Pattern TOKEN = Pattern.compile("\\[(?:" + typeAlternatives() + ")_\\d+(?:_AGE\\d{1,3})?]");

    private Restorer() {
    }

    public static Result restore(String text, Map<String, String> mapping) {
        return restore(text, mapping::get);
    }

    /** Восстановление с произвольным поиском значения по метке; {@code null} — метка неизвестна. */
    public static Result restore(String text, Function<String, String> originalOf) {
        Matcher m = TOKEN.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        Set<String> unknown = new TreeSet<>();
        int restored = 0;
        while (m.find()) {
            String original = originalOf.apply(m.group());
            if (original == null) {
                unknown.add(m.group());
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            } else {
                restored++;
                m.appendReplacement(sb, Matcher.quoteReplacement(original));
            }
        }
        m.appendTail(sb);
        return new Result(sb.toString(), restored, unknown);
    }

    private static String typeAlternatives() {
        StringBuilder sb = new StringBuilder();
        for (PiiType type : PiiType.values()) {
            if (!sb.isEmpty()) {
                sb.append('|');
            }
            sb.append(type.name());
        }
        return sb.toString();
    }
}
