package ru.pii.anonymizer.document;

import java.util.List;

import ru.pii.anonymizer.detect.TextAnonymizer.Replacement;

/**
 * Замены в тексте, разбитом на фрагменты с разным оформлением (runs в Word и PowerPoint, span в ODT).
 *
 * <p>Одно ФИО может оказаться в трёх фрагментах — «Ива», «нов Иван», «ович». Поиск идёт по склеенному
 * тексту, а замена раскладывается обратно: метка пишется в первый фрагмент совпадения, остаток
 * совпадения удаляется из следующих. Оформление первого фрагмента переходит на метку,
 * остальной текст сохраняет своё, число и порядок фрагментов не меняются — документ не ломается.
 */
final class TextRuns {

    private TextRuns() {
    }

    /** Склеенный текст фрагментов. */
    static String join(String[] texts) {
        return String.join("", texts);
    }

    /**
     * Новые тексты фрагментов после замен {@code plan} (позиции — в склеенном тексте).
     * Фрагменты, которые не изменились, возвращаются теми же объектами строк.
     */
    static String[] apply(String[] texts, List<Replacement> plan) {
        String[] result = texts.clone();
        int[] offsets = new int[texts.length];
        int[] lengths = new int[texts.length];
        int position = 0;
        for (int i = 0; i < texts.length; i++) {
            offsets[i] = position;
            lengths[i] = texts[i].length();
            position += lengths[i];
        }
        // С конца: замена не сдвигает позиции более ранних совпадений
        for (Replacement r : plan.reversed()) {
            int first = runAt(offsets, lengths, r.start());
            int last = runAt(offsets, lengths, r.end() - 1);
            int startInFirst = r.start() - offsets[first];
            int endInLast = r.end() - offsets[last];

            if (first == last) {
                result[first] = result[first].substring(0, startInFirst) + r.token() + result[first].substring(endInLast);
            } else {
                result[last] = result[last].substring(endInLast);
                for (int i = first + 1; i < last; i++) {
                    result[i] = "";
                }
                result[first] = result[first].substring(0, startInFirst) + r.token();
            }
        }
        return result;
    }

    /**
     * Индекс фрагмента, в который попадает символ с позицией {@code position} склеенного текста.
     * Используются исходные длины: тексты фрагментов меняются по ходу замен, но совпадения
     * обрабатываются с конца и более ранние фрагменты к этому моменту не тронуты.
     */
    private static int runAt(int[] offsets, int[] lengths, int position) {
        for (int i = 0; i < offsets.length; i++) {
            if (position >= offsets[i] && position < offsets[i] + lengths[i]) {
                return i;
            }
        }
        throw new IllegalStateException("Позиция " + position + " вне текста абзаца");
    }
}
