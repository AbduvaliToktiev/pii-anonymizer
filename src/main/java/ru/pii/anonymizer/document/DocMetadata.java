package ru.pii.anonymizer.document;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.util.LittleEndian;

/**
 * Стирает строки служебной таблицы SttbfAssoc в файле doc: автор, последний редактор, путь к шаблону,
 * название, тема. Word хранит их там помимо свойств документа, а POI эту таблицу не разбирает
 * и при сохранении копирует как есть.
 *
 * <p>Строки заменяются пробелами той же длины прямо в потоке таблицы — до разбора документа POI,
 * так что ни одно смещение в файле не меняется.
 */
final class DocMetadata {

    /** Номер пары fc/lcb таблицы SttbfAssoc в FibRgFcLcb97. */
    private static final int STTBF_ASSOC = 32;
    /** Флаг fWhichTblStm в FibBase: таблица в потоке «1Table», а не «0Table». */
    private static final int WHICH_TABLE_STREAM = 0x0200;

    private DocMetadata() {
    }

    static byte[] scrub(byte[] doc) throws IOException {
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(doc))) {
            DirectoryNode root = fs.getRoot();
            if (!root.hasEntry("WordDocument")) {
                return doc;
            }
            byte[] main = read(root, "WordDocument");
            if (main.length < 64) {
                return doc;
            }
            String tableName = (LittleEndian.getUShort(main, 0x0A) & WHICH_TABLE_STREAM) != 0 ? "1Table" : "0Table";
            if (!root.hasEntry(tableName)) {
                return doc;
            }
            // FibBase (32 байта), csw + fibRgW, cslw + fibRgLw, cbRgFcLcb + fibRgFcLcb
            int pos = 32;
            int csw = LittleEndian.getUShort(main, pos);
            pos += 2 + csw * 2;
            int cslw = LittleEndian.getUShort(main, pos);
            pos += 2 + cslw * 4;
            int pairs = LittleEndian.getUShort(main, pos);
            pos += 2;
            if (pairs <= STTBF_ASSOC || pos + (STTBF_ASSOC + 1) * 8 > main.length) {
                return doc;
            }
            int fc = LittleEndian.getInt(main, pos + STTBF_ASSOC * 8);
            int lcb = LittleEndian.getInt(main, pos + STTBF_ASSOC * 8 + 4);
            byte[] table = read(root, tableName);
            if (lcb < 6 || fc < 0 || (long) fc + lcb > table.length || !blankStrings(table, fc, fc + lcb)) {
                return doc;
            }
            root.createOrUpdateDocument(tableName, new ByteArrayInputStream(table));
            ByteArrayOutputStream out = new ByteArrayOutputStream(doc.length);
            fs.writeFilesystem(out);
            return out.toByteArray();
        }
    }

    /** Строки STTB в UTF-16 (fExtend = 0xFFFF) заменяются пробелами; {@code false} — формат не тот. */
    private static boolean blankStrings(byte[] table, int start, int end) {
        if (LittleEndian.getUShort(table, start) != 0xFFFF) {
            return false;
        }
        int count = LittleEndian.getUShort(table, start + 2);
        int extra = LittleEndian.getUShort(table, start + 4);
        int p = start + 6;
        for (int i = 0; i < count && p + 2 <= end; i++) {
            int chars = LittleEndian.getUShort(table, p);
            p += 2;
            for (int c = 0; c < chars && p + 2 * c + 1 < end; c++) {
                table[p + 2 * c] = ' ';
                table[p + 2 * c + 1] = 0;
            }
            p += chars * 2 + extra;
        }
        return true;
    }

    private static byte[] read(DirectoryNode root, String name) throws IOException {
        try (InputStream in = root.createDocumentInputStream((DocumentEntry) root.getEntry(name))) {
            return in.readAllBytes();
        }
    }
}
