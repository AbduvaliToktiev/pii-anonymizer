package ru.pii.anonymizer.gateway;

import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ru.pii.anonymizer.pseudonym.Restorer;

/**
 * Восстановление меток в тексте, который приходит кусками (потоковый ответ модели).
 *
 * <p>Метка может разорваться между кусками: «…получает [PERS» + «ON_2] больше…».
 * Поэтому хвост куска, похожий на начало метки, придерживается до следующего куска
 * и отдаётся вместе с ним. Один экземпляр — на один блок ответа.
 */
final class StreamingRestorer {

    /** Незавершённая метка в конце текста: «[», «[PER», «[BIRTHDAT», «[PERSON_», «[PERSON_K7M2». */
    private static final Pattern PARTIAL_TOKEN_AT_END = Pattern.compile(
            "\\[[A-Z]{0,12}(?:_[0-9A-Z]{0,16}(?:_[A-Z]{0,3}\\d{0,3})?)?$"); // хвост «_AGE41» у даты рождения

    private final Function<String, String> originalOf;
    private final UnaryOperator<String> encode;
    private final StringBuilder pending = new StringBuilder();

    /**
     * @param originalOf исходное значение по метке ({@code null} — неизвестная метка, остаётся как есть)
     * @param encode     преобразование подставляемого значения: для текста — без изменений,
     *                   для фрагментов JSON — экранирование кавычек и обратных слэшей
     */
    StreamingRestorer(Function<String, String> originalOf, UnaryOperator<String> encode) {
        this.originalOf = originalOf;
        this.encode = encode;
    }

    /** Принимает очередной кусок и возвращает то, что уже можно отдать клиенту. */
    String push(String chunk) {
        pending.append(chunk);
        String text = pending.toString();
        Matcher partial = PARTIAL_TOKEN_AT_END.matcher(text);
        int cut = partial.find() ? partial.start() : text.length();
        pending.setLength(0);
        pending.append(text, cut, text.length());
        return restore(text.substring(0, cut));
    }

    /** Отдаёт всё придержанное — вызывается в конце блока. */
    String flush() {
        String rest = pending.toString();
        pending.setLength(0);
        return restore(rest);
    }

    private String restore(String text) {
        if (text.isEmpty()) {
            return text;
        }
        return Restorer.restore(text, token -> {
            String original = originalOf.apply(token);
            return original == null ? null : encode.apply(original);
        }).text();
    }
}
