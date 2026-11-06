package ru.pii.anonymizer.document;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки сборки обезличенного PDF.
 *
 * @param fontPath путь к TTF-шрифту с кириллицей; если не задан, ищется системный (Arial, DejaVu Sans)
 * @param fontSize размер шрифта в пунктах
 */
@ConfigurationProperties("anonymizer.pdf")
public record PdfProperties(String fontPath, Float fontSize) {

    public PdfProperties {
        if (fontSize == null || fontSize <= 0) {
            fontSize = 10f;
        }
    }
}
