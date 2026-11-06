package ru.pii.anonymizer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PiiAnonymizerApplication {

    public static void main(String[] args) {
        // Хранилище сертификатов Windows: иначе JDK не доверяет антивирусу/прокси, проверяющему HTTPS.
        // Задаётся здесь, чтобы при запуске из IDEA не нужны были VM options.
        if (System.getProperty("os.name", "").startsWith("Windows")
                && System.getProperty("javax.net.ssl.trustStoreType") == null) {
            System.setProperty("javax.net.ssl.trustStoreType", "Windows-ROOT");
        }
        SpringApplication.run(PiiAnonymizerApplication.class, args);
    }
}
