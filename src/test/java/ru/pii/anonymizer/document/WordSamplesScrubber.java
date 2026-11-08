package ru.pii.anonymizer.document;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.poi.hpsf.PropertySetFactory;
import org.apache.poi.hpsf.SummaryInformation;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Word при сохранении doc и odt записывает в файл имя пользователя компьютера (последний редактор).
 * Эта утилита ставит в примеры вымышленного автора, текст не трогает. Запускается вручную после того,
 * как Word сделал doc и odt из docx:
 * {@code mvn test -Dtest=WordSamplesScrubber -Dscrub.dir=samples -Dsurefire.failIfNoSpecifiedTests=false}
 */
@EnabledIfSystemProperty(named = "scrub.dir", matches = ".+")
class WordSamplesScrubber {

    private static final String AUTHOR = "Отдел кадров";

    @Test
    void scrub() throws Exception {
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("scrub.dir")))) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith(".doc")) {
                    Files.write(file, doc(Files.readAllBytes(file)));
                } else if (name.endsWith(".odt")) {
                    Files.write(file, odt(Files.readAllBytes(file)));
                }
            }
        }
    }

    private static byte[] doc(byte[] content) throws Exception {
        byte[] scrubbed = DocMetadata.scrub(content);
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(scrubbed))) {
            DirectoryNode root = fs.getRoot();
            if (root.hasEntry(SummaryInformation.DEFAULT_STREAM_NAME)) {
                SummaryInformation info = (SummaryInformation) PropertySetFactory.create(root,
                        SummaryInformation.DEFAULT_STREAM_NAME);
                info.setAuthor(AUTHOR);
                info.setLastAuthor(AUTHOR);
                info.write(root, SummaryInformation.DEFAULT_STREAM_NAME);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            fs.writeFilesystem(out);
            return out.toByteArray();
        }
    }

    private static byte[] odt(byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(content));
             ZipOutputStream zip = new ZipOutputStream(out)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                byte[] data = in.readAllBytes();
                if (entry.getName().equals("meta.xml")) {
                    String xml = new String(data, StandardCharsets.UTF_8);
                    for (String tag : List.of("dc:creator", "meta:initial-creator")) {
                        xml = xml.replaceAll("<" + tag + ">[^<]*</" + tag + ">", "<" + tag + ">" + AUTHOR + "</" + tag + ">");
                    }
                    data = xml.getBytes(StandardCharsets.UTF_8);
                }
                ZipEntry copy = new ZipEntry(entry.getName());
                if (entry.getMethod() == ZipEntry.STORED) {
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    copy.setMethod(ZipEntry.STORED);
                    copy.setSize(data.length);
                    copy.setCompressedSize(data.length);
                    copy.setCrc(crc.getValue());
                }
                zip.putNextEntry(copy);
                zip.write(data);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
