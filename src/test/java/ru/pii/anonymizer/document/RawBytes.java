package ru.pii.anonymizer.document;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Поиск ПДн в «сырых» байтах файла, а не в тексте, который покажет редактор: так ловятся остатки
 * в метаданных, сохранённых результатах формул, миниатюрах, связях и старых фрагментах текста.
 * Zip-форматы (xlsx, docx, pptx, odt) распаковываются, остальные просматриваются в UTF-8 и UTF-16LE.
 */
final class RawBytes {

    private RawBytes() {
    }

    /** Значения из {@code values}, найденные где-либо внутри файла. */
    static List<String> leaked(byte[] file, List<String> values) {
        List<String> texts = new ArrayList<>();
        if (file.length > 4 && file[0] == 'P' && file[1] == 'K') {
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    texts.addAll(decodings(readAll(zip)));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } else {
            texts.addAll(decodings(file));
        }
        List<String> found = new ArrayList<>();
        for (String value : values) {
            if (!value.isBlank() && texts.stream().anyMatch(t -> t.contains(value))) {
                found.add(value);
            }
        }
        return found;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        return in.readAllBytes();
    }

    private static List<String> decodings(byte[] bytes) {
        byte[] shifted = bytes.length > 1 ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
        return List.of(new String(bytes, StandardCharsets.UTF_8),
                new String(bytes, StandardCharsets.UTF_16LE),
                new String(shifted, StandardCharsets.UTF_16LE));
    }
}
