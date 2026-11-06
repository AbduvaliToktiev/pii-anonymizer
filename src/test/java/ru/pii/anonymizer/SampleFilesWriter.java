package ru.pii.anonymizer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Записывает примеры документов с вымышленными ПДн для ручной проверки.
 * В обычной сборке не запускается:
 * {@code mvn test -Dtest=SampleFilesWriter -Dsamples.dir=samples}
 */
@EnabledIfSystemProperty(named = "samples.dir", matches = ".+")
class SampleFilesWriter {

    @Test
    void writeSamples() throws IOException {
        Path dir = Path.of(System.getProperty("samples.dir"));
        Files.createDirectories(dir);
        Files.write(dir.resolve("Зарплаты.xlsx"), SampleDocuments.salaryXlsx());
        Files.write(dir.resolve("Зарплаты.xls"), SampleDocuments.salaryXls());
        Files.write(dir.resolve("Трудовой договор.docx"), SampleDocuments.contractDocx());
        SampleDocuments.systemFont().ifPresent(font -> {
            try {
                Files.write(dir.resolve("Служебная записка.pdf"), SampleDocuments.memoPdf(font));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }
}
