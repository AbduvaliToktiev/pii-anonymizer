package ru.pii.anonymizer.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ru.pii.anonymizer.detect.RussianPiiDetectors;
import ru.pii.anonymizer.detect.TextAnonymizer;
import ru.pii.anonymizer.pseudonym.PseudonymRegistryFactory;

@Configuration(proxyBeanMethods = false)
public class AnonymizerConfig {

    @Bean
    TextAnonymizer textAnonymizer() {
        return new TextAnonymizer(RussianPiiDetectors.all());
    }

    @Bean
    PseudonymRegistryFactory pseudonymRegistryFactory(BirthDateProperties birthDate) {
        return new PseudonymRegistryFactory(birthDate.mode(), Clock.systemDefaultZone());
    }
}
