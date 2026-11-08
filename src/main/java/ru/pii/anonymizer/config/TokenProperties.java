package ru.pii.anonymizer.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Секретный ключ кодов меток ({@code [PERSON_K7M2QX9D]}).
 *
 * @param key     ключ в base64 (не короче 16 байт); задаётся переменной {@code ANONYMIZER_TOKEN_KEY}.
 *                Если не задан, ключ берётся из {@code keyFile}
 * @param keyFile файл с ключом; создаётся со случайным ключом при первом запуске
 */
@ConfigurationProperties("anonymizer.token")
public record TokenProperties(String key, Path keyFile) {

    public TokenProperties {
        if (keyFile == null) {
            keyFile = Path.of("data", "token.key");
        }
    }
}
