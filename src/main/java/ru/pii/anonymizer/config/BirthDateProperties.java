package ru.pii.anonymizer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import ru.pii.anonymizer.pseudonym.BirthDateMode;

/**
 * Настройки обезличивания дат рождения.
 *
 * @param mode {@code date} — только метка; {@code age} — метка с возрастом в полных годах
 */
@ConfigurationProperties("anonymizer.birth-date")
public record BirthDateProperties(BirthDateMode mode) {

    public BirthDateProperties {
        if (mode == null) {
            mode = BirthDateMode.AGE;
        }
    }
}
