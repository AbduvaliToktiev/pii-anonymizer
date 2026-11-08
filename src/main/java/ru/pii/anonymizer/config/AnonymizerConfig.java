package ru.pii.anonymizer.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ru.pii.anonymizer.detect.RussianPiiDetectors;
import ru.pii.anonymizer.detect.TextAnonymizer;
import ru.pii.anonymizer.pseudonym.PseudonymRegistryFactory;
import ru.pii.anonymizer.pseudonym.TokenKey;

@Configuration(proxyBeanMethods = false)
public class AnonymizerConfig {

    @Bean
    TextAnonymizer textAnonymizer() {
        return new TextAnonymizer(RussianPiiDetectors.all());
    }

    @Bean
    TokenKey tokenKey(TokenProperties token) {
        return TokenKey.load(token.key(), token.keyFile());
    }

    @Bean
    PseudonymRegistryFactory pseudonymRegistryFactory(BirthDateProperties birthDate, TokenKey tokenKey) {
        return new PseudonymRegistryFactory(birthDate.mode(), Clock.systemDefaultZone(), tokenKey);
    }
}
