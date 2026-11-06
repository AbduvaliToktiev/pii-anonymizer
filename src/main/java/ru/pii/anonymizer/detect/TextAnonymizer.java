package ru.pii.anonymizer.detect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import ru.pii.anonymizer.pseudonym.PseudonymRegistry;

/**
 * Находит ПДн всеми детекторами и заменяет их метками.
 * Потокобезопасен: состояние документа живёт в переданном {@link PseudonymRegistry}.
 */
public final class TextAnonymizer {

    /** Замена фрагмента [start, end) исходного текста на метку. */
    public record Replacement(int start, int end, String token) {
    }

    private static final Comparator<Finding> BY_PRIORITY = Comparator
            .comparingInt(Finding::length).reversed()
            .thenComparing(Finding::type)
            .thenComparingInt(Finding::start);

    private final List<PiiDetector> detectors;

    public TextAnonymizer(List<PiiDetector> detectors) {
        this.detectors = List.copyOf(detectors);
    }

    /**
     * Все находки без пересечений, отсортированные по позиции.
     * При пересечении побеждает более длинная находка, при равной длине — тип с более высоким приоритетом.
     */
    public List<Finding> detect(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Finding> candidates = new ArrayList<>();
        for (PiiDetector detector : detectors) {
            candidates.addAll(detector.detect(text));
        }
        candidates.sort(BY_PRIORITY);

        List<Finding> accepted = new ArrayList<>();
        for (Finding candidate : candidates) {
            if (accepted.stream().noneMatch(candidate::overlaps)) {
                accepted.add(candidate);
            }
        }
        accepted.sort(Comparator.comparingInt(Finding::start));
        return accepted;
    }

    /** План замен для текста; нужен там, где текст разбит на части (фрагменты абзаца в Word). */
    public List<Replacement> plan(String text, PseudonymRegistry registry) {
        return detect(text).stream()
                .map(f -> new Replacement(f.start(), f.end(), registry.tokenFor(f.type(), f.value())))
                .toList();
    }

    public String anonymize(String text, PseudonymRegistry registry) {
        List<Replacement> plan = plan(text, registry);
        if (plan.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        int cursor = 0;
        for (Replacement r : plan) {
            sb.append(text, cursor, r.start()).append(r.token());
            cursor = r.end();
        }
        return sb.append(text, cursor, text.length()).toString();
    }
}
