package ru.pii.anonymizer.detect;

import java.util.List;

/**
 * Детектор одного вида персональных данных в произвольном тексте.
 * Реализации не должны хранить состояние: один экземпляр используется всеми запросами.
 */
public interface PiiDetector {

    List<Finding> detect(String text);
}
