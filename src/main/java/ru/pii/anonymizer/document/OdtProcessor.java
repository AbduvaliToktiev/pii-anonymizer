package ru.pii.anonymizer.document;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;
import org.xml.sax.SAXException;

import ru.pii.anonymizer.detect.TextAnonymizer;
import ru.pii.anonymizer.detect.TextAnonymizer.Replacement;
import ru.pii.anonymizer.pseudonym.PseudonymRegistry;

/**
 * Обезличивание OpenDocument Text (odt) с сохранением оформления.
 *
 * <p>odt — zip-архив с XML. Текст лежит в абзацах {@code text:p} и заголовках {@code text:h}
 * основного содержимого ({@code content.xml}) и колонтитулов ({@code styles.xml}). Абзац склеивается
 * из текстовых узлов (в том числе внутри {@code text:span} и ссылок), а замены раскладываются обратно
 * ({@link TextRuns}) — структура XML и стили не меняются. Пробелы {@code text:s}, табуляции и переносы
 * строк, целиком попавшие в ФИО, удаляются вместе с ним.
 *
 * <p>Ссылки с ПДн в адресе обезличиваются, автор в {@code meta.xml} стирается, миниатюра первой
 * страницы заменяется пустой картинкой. Архив собирается в исходном порядке, {@code mimetype}
 * остаётся первым и несжатым, как требует формат.
 */
@Component
public class OdtProcessor implements DocumentProcessor {

    static final String TEXT_NS = "urn:oasis:names:tc:opendocument:xmlns:text:1.0";
    private static final String XLINK_NS = "http://www.w3.org/1999/xlink";
    private static final String DC_NS = "http://purl.org/dc/elements/1.1/";
    private static final String META_NS = "urn:oasis:names:tc:opendocument:xmlns:meta:1.0";

    private static final Set<String> TEXT_PARTS = Set.of("content.xml", "styles.xml");

    /** Белый PNG 1×1 вместо миниатюры. */
    private static final byte[] BLANK_PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=");

    private final TextAnonymizer anonymizer;

    public OdtProcessor(TextAnonymizer anonymizer) {
        this.anonymizer = anonymizer;
    }

    @Override
    public boolean supports(DocumentFormat format) {
        return format == DocumentFormat.ODT;
    }

    @Override
    public byte[] process(byte[] content, PseudonymRegistry registry) throws IOException {
        List<Entry> entries = read(content);
        if (entries.stream().noneMatch(e -> e.name.equals("content.xml"))) {
            throw new DocumentProcessingException("В файле нет content.xml — это не документ OpenDocument");
        }
        for (Entry entry : entries) {
            if (TEXT_PARTS.contains(entry.name)) {
                entry.data = processXml(entry.data, registry);
            } else if (entry.name.equals("meta.xml")) {
                entry.data = clearAuthor(entry.data);
            } else if (entry.name.startsWith("Thumbnails/")) {
                entry.data = BLANK_PNG;
            }
        }
        return write(entries);
    }

    // ---------- текст ----------

    private byte[] processXml(byte[] xml, PseudonymRegistry registry) throws IOException {
        Document doc = parse(xml);
        for (String tag : List.of("p", "h")) {
            NodeList paragraphs = doc.getElementsByTagNameNS(TEXT_NS, tag);
            for (int i = 0; i < paragraphs.getLength(); i++) {
                processParagraph((Element) paragraphs.item(i), registry);
            }
        }
        NodeList links = doc.getElementsByTagNameNS(TEXT_NS, "a");
        for (int i = 0; i < links.getLength(); i++) {
            Element link = (Element) links.item(i);
            String href = link.getAttributeNS(XLINK_NS, "href");
            if (!href.isEmpty() && !anonymizer.detect(href).isEmpty()) {
                link.setAttributeNS(XLINK_NS, "xlink:href", anonymizer.anonymize(href, registry));
            }
        }
        return serialize(doc);
    }

    private void processParagraph(Element paragraph, PseudonymRegistry registry) {
        List<Node> segments = new ArrayList<>();
        collect(paragraph, segments);
        if (segments.isEmpty()) {
            return;
        }
        String[] texts = new String[segments.size()];
        for (int i = 0; i < texts.length; i++) {
            texts[i] = textOf(segments.get(i));
        }
        List<Replacement> plan = anonymizer.plan(TextRuns.join(texts), registry);
        if (plan.isEmpty()) {
            return;
        }
        String[] changed = TextRuns.apply(texts, plan);
        for (int i = 0; i < texts.length; i++) {
            if (changed[i] != texts[i]) {
                update(segments.get(i), changed[i]);
            }
        }
    }

    /**
     * Части текста абзаца по порядку: текстовые узлы и пробелы, табуляции, переносы.
     * Вложенные абзацы (в сносках, надписях) не берутся — они обходятся как отдельные абзацы.
     */
    private static void collect(Node node, List<Node> segments) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Text) {
                segments.add(child);
            } else if (child instanceof Element e && TEXT_NS.equals(e.getNamespaceURI())) {
                switch (e.getLocalName()) {
                    case "s", "tab", "line-break" -> segments.add(e);
                    case "p", "h", "note", "annotation", "tracked-changes" -> {
                        // вложенные абзацы и служебные блоки — отдельно
                    }
                    default -> collect(e, segments);
                }
            } else if (child instanceof Element e) {
                collect(e, segments);
            }
        }
    }

    private static String textOf(Node segment) {
        if (segment instanceof Text text) {
            return text.getData();
        }
        Element e = (Element) segment;
        return switch (e.getLocalName()) {
            case "s" -> " ".repeat(spaces(e));
            case "tab" -> "\t";
            default -> "\n";
        };
    }

    private static int spaces(Element s) {
        String c = s.getAttributeNS(TEXT_NS, "c");
        try {
            return c.isEmpty() ? 1 : Math.max(1, Integer.parseInt(c));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static void update(Node segment, String text) {
        if (segment instanceof Text node) {
            node.setData(text);
        } else if (text.isEmpty()) {
            segment.getParentNode().removeChild(segment);
        } else if ("s".equals(segment.getLocalName())) {
            ((Element) segment).setAttributeNS(TEXT_NS, "text:c", Integer.toString(text.length()));
        }
    }

    private static byte[] clearAuthor(byte[] xml) throws IOException {
        Document doc = parse(xml);
        for (String[] tag : new String[][] {{DC_NS, "creator"}, {META_NS, "initial-creator"}}) {
            NodeList nodes = doc.getElementsByTagNameNS(tag[0], tag[1]);
            for (int i = 0; i < nodes.getLength(); i++) {
                nodes.item(i).setTextContent("");
            }
        }
        return serialize(doc);
    }

    // ---------- XML ----------

    static Document parse(byte[] xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // Внешние сущности запрещены: файл пришёл от пользователя
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException | SAXException e) {
            throw new DocumentProcessingException("Не удалось прочитать XML документа: файл повреждён", e);
        }
    }

    private static byte[] serialize(Document doc) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(doc), new StreamResult(out));
            return out.toByteArray();
        } catch (TransformerException e) {
            throw new IllegalStateException("Не удалось записать XML документа", e);
        }
    }

    // ---------- архив ----------

    private static final class Entry {
        final String name;
        final boolean stored;
        byte[] data;

        Entry(String name, boolean stored, byte[] data) {
            this.name = name;
            this.stored = stored;
            this.data = data;
        }
    }

    static List<Entry> read(byte[] content) throws IOException {
        List<Entry> entries = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    entries.add(new Entry(entry.getName(), false, new byte[0]));
                    continue;
                }
                entries.add(new Entry(entry.getName(), entry.getMethod() == ZipEntry.STORED, zip.readAllBytes()));
            }
        } catch (ZipException e) {
            throw new DocumentProcessingException("Не удалось распаковать документ: файл повреждён", e);
        }
        return entries;
    }

    private static byte[] write(List<Entry> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Entry e : entries) {
                ZipEntry entry = new ZipEntry(e.name);
                if (e.stored || e.name.equals("mimetype")) {
                    CRC32 crc = new CRC32();
                    crc.update(e.data);
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(e.data.length);
                    entry.setCompressedSize(e.data.length);
                    entry.setCrc(crc.getValue());
                }
                zip.putNextEntry(entry);
                zip.write(e.data);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** Текст абзацев content.xml — для передачи в модель. */
    static String text(byte[] odt) throws IOException {
        for (Entry entry : read(odt)) {
            if (entry.name.equals("content.xml")) {
                Document doc = parse(entry.data);
                StringBuilder sb = new StringBuilder();
                appendParagraphs(doc.getDocumentElement(), sb);
                return sb.toString().strip();
            }
        }
        return "";
    }

    private static void appendParagraphs(Node node, StringBuilder sb) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element e && TEXT_NS.equals(e.getNamespaceURI())
                    && ("p".equals(e.getLocalName()) || "h".equals(e.getLocalName()))) {
                List<Node> segments = new ArrayList<>();
                collect(e, segments);
                for (Node segment : segments) {
                    sb.append(textOf(segment));
                }
                sb.append('\n');
                appendNested(e, sb);
            } else if (child instanceof Element e) {
                appendParagraphs(e, sb);
            }
        }
    }

    /** Абзацы внутри сносок и надписей абзаца. */
    private static void appendNested(Element paragraph, StringBuilder sb) {
        for (Node child = paragraph.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element e) {
                appendParagraphs(e, sb);
            }
        }
    }
}
