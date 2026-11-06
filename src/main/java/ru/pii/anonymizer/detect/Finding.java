package ru.pii.anonymizer.detect;

/**
 * Найденный фрагмент персональных данных: позиция в тексте [start, end) и исходное значение.
 */
public record Finding(PiiType type, int start, int end, String value) {

    public Finding {
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Некорректный диапазон: [" + start + ", " + end + ")");
        }
    }

    public int length() {
        return end - start;
    }

    public boolean overlaps(Finding other) {
        return start < other.end && other.start < end;
    }
}
