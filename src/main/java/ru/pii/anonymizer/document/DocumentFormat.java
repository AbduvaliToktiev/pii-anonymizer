package ru.pii.anonymizer.document;

import java.util.Arrays;
import java.util.Locale;

/**
 * Поддерживаемые форматы. Формат определяется по расширению и подтверждается
 * сигнатурой файла, чтобы переименованный файл не попал не в тот обработчик.
 */
public enum DocumentFormat {

    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Signature.ZIP),
    XLS("xls", "application/vnd.ms-excel", Signature.OLE2),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", Signature.ZIP),
    PDF("pdf", "application/pdf", Signature.PDF);

    private enum Signature {
        ZIP(new byte[] {0x50, 0x4B, 0x03, 0x04}),
        OLE2(new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1}),
        PDF(new byte[] {0x25, 0x50, 0x44, 0x46});

        private final byte[] magic;

        Signature(byte[] magic) {
            this.magic = magic;
        }

        boolean matches(byte[] content) {
            return content.length >= magic.length
                    && Arrays.equals(content, 0, magic.length, magic, 0, magic.length);
        }
    }

    private final String extension;
    private final String contentType;
    private final Signature signature;

    DocumentFormat(String extension, String contentType, Signature signature) {
        this.extension = extension;
        this.contentType = contentType;
        this.signature = signature;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    public static DocumentFormat detect(String fileName, byte[] content) {
        String ext = extensionOf(fileName);
        DocumentFormat format = Arrays.stream(values())
                .filter(f -> f.extension.equals(ext))
                .findFirst()
                .orElseThrow(() -> new UnsupportedDocumentException(
                        "Формат «." + ext + "» не поддерживается. Поддерживаются: xlsx, xls, docx, pdf"));
        if (!format.signature.matches(content)) {
            throw new UnsupportedDocumentException(
                    "Содержимое файла не похоже на ." + ext + " — возможно, файл повреждён или переименован");
        }
        return format;
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
