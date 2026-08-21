package com.comicatlas.worker.importer;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/** 安全解析漫画标准 ComicInfo.xml；缺少该文件不是错误。 */
public final class ComicInfoParser {

    private static final String FILE_NAME = "ComicInfo.xml";

    private ComicInfoParser() {
    }

    public static Optional<ComicInfoMetadata> parse(Path comicRoot) throws IOException {
        Path file = findFile(comicRoot);
        if (file == null) {
            return Optional.empty();
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(file.toFile());
            Element root = document.getDocumentElement();
            return Optional.of(new ComicInfoMetadata(
                    value(root, "Series"), value(root, "Title"), value(root, "Number"),
                    firstNonBlank(value(root, "Writer"), value(root, "Penciller")),
                    tags(root)));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("ComicInfo.xml 解析失败: " + file.getFileName(), e);
        }
    }

    private static Path findFile(Path root) throws IOException {
        Path direct = root.resolve(FILE_NAME);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        Path lowerCase = root.resolve("comicinfo.xml");
        return Files.isRegularFile(lowerCase) ? lowerCase : null;
    }

    private static String value(Element root, String name) {
        var nodes = root.getElementsByTagName(name);
        if (nodes.getLength() == 0) {
            return null;
        }
        String value = nodes.item(0).getTextContent();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static List<String> tags(Element root) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        addTokens(result, value(root, "Genre"));
        addTokens(result, value(root, "Tags"));
        return List.copyOf(result);
    }

    private static void addTokens(LinkedHashSet<String> result, String raw) {
        if (raw == null) {
            return;
        }
        for (String token : raw.split("[,;]")) {
            if (!token.isBlank()) {
                result.add(token.trim());
            }
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
