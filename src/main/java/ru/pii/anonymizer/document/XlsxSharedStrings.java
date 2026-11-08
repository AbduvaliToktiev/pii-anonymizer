package ru.pii.anonymizer.document;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Удаляет из xlsx строки общей таблицы ({@code xl/sharedStrings.xml}), на которые не ссылается
 * ни одна ячейка, и перенумеровывает ссылки в листах.
 *
 * <p>POI не удаляет строки из этой таблицы: после замены ФИО на метки в файле оставались бы исходные ФИО
 * (их стирает {@code ExcelProcessor}) или тысячи пустых записей. Последние сжимаются почти до нуля,
 * и защита POI от zip-бомб отказывается такой файл читать. После уплотнения в таблице только
 * используемые строки, а файл ничем не отличается от сохранённого в Excel.
 *
 * <p>Ячейки со ссылкой на таблицу записаны как {@code <c r="B2" t="s" s="4"><v>17</v></c>};
 * формулы со строковым результатом ({@code t="str"}) хранят результат в самой ячейке и не затрагиваются.
 */
final class XlsxSharedStrings {

    private static final String STRINGS = "xl/sharedStrings.xml";
    private static final Pattern SHEET = Pattern.compile("xl/(?:worksheets|chartsheets)/[^/]+\\.xml");
    private static final Pattern ITEM = Pattern.compile("<si\\b[^>]*/>|<si\\b[^>]*>.*?</si>", Pattern.DOTALL);
    private static final Pattern STRING_CELL = Pattern.compile("(<c\\b[^>]*\\bt=\"s\"[^>]*>\\s*<v>)(\\d+)(</v>)");
    /** Счётчики в заголовке таблицы: пересчитываются заново. */
    private static final Pattern COUNTS = Pattern.compile("\\s(?:count|uniqueCount)=\"\\d+\"");

    private XlsxSharedStrings() {
    }

    static byte[] compact(byte[] xlsx) throws IOException {
        List<String> names = new ArrayList<>();
        List<byte[]> data = new ArrayList<>();
        List<Boolean> stored = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
                data.add(zip.readAllBytes());
                stored.add(entry.getMethod() == ZipEntry.STORED);
            }
        }
        int stringsIndex = names.indexOf(STRINGS);
        if (stringsIndex < 0) {
            return xlsx;
        }

        // Какие строки используются и сколько раз
        TreeSet<Integer> used = new TreeSet<>();
        int references = 0;
        for (int i = 0; i < names.size(); i++) {
            if (SHEET.matcher(names.get(i)).matches()) {
                Matcher m = STRING_CELL.matcher(new String(data.get(i), StandardCharsets.UTF_8));
                while (m.find()) {
                    used.add(Integer.parseInt(m.group(2)));
                    references++;
                }
            }
        }

        String sst = new String(data.get(stringsIndex), StandardCharsets.UTF_8);
        Matcher items = ITEM.matcher(sst);
        List<int[]> spans = new ArrayList<>();
        while (items.find()) {
            spans.add(new int[] {items.start(), items.end()});
        }
        if (spans.isEmpty() || used.isEmpty() || used.size() == spans.size() || used.last() >= spans.size()) {
            return xlsx; // нечего удалять или ссылки не сходятся с таблицей — файл не трогаем
        }

        int[] newIndex = new int[spans.size()];
        StringBuilder kept = new StringBuilder(sst.length());
        int next = 0;
        for (int old = 0; old < spans.size(); old++) {
            if (used.contains(old)) {
                newIndex[old] = next++;
                kept.append(sst, spans.get(old)[0], spans.get(old)[1]);
            }
        }
        String head = sst.substring(0, spans.getFirst()[0]);
        String tail = sst.substring(spans.getLast()[1]);
        head = COUNTS.matcher(head).replaceAll("");
        head = head.replaceFirst("<sst\\b", "<sst count=\"" + references + "\" uniqueCount=\"" + next + "\"");
        data.set(stringsIndex, (head + kept + tail).getBytes(StandardCharsets.UTF_8));

        for (int i = 0; i < names.size(); i++) {
            if (SHEET.matcher(names.get(i)).matches()) {
                Matcher m = STRING_CELL.matcher(new String(data.get(i), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                while (m.find()) {
                    int index = newIndex[Integer.parseInt(m.group(2))];
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + index + m.group(3)));
                }
                m.appendTail(sb);
                data.set(i, sb.toString().getBytes(StandardCharsets.UTF_8));
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(xlsx.length);
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < names.size(); i++) {
                ZipEntry entry = new ZipEntry(names.get(i));
                byte[] bytes = data.get(i);
                if (stored.get(i)) {
                    CRC32 crc = new CRC32();
                    crc.update(bytes);
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(bytes.length);
                    entry.setCompressedSize(bytes.length);
                    entry.setCrc(crc.getValue());
                }
                zip.putNextEntry(entry);
                zip.write(bytes);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
