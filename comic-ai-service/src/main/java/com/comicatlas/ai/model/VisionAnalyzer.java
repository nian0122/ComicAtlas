package com.comicatlas.ai.model;

import com.comicatlas.ai.analysis.SamplePage;
import com.comicatlas.ai.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;

/** LangChain4j 视觉模型适配器。业务层只接收 JSON 文本。 */
@Service
public class VisionAnalyzer {
    private static final int MAX_IMAGE_EDGE = 1600;
    private static final float JPEG_QUALITY = 0.78f;
    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    public VisionAnalyzer(AiProperties properties, ObjectMapper objectMapper) { this.properties = properties; this.objectMapper = objectMapper; }
    public String analyze(List<SamplePage> pages) throws IOException {
        if (properties.model().apiKey() == null || properties.model().apiKey().isBlank() || properties.model().modelName() == null || properties.model().modelName().isBlank()) {
            throw new IllegalStateException("AI_API_KEY 和 AI_MODEL 必须配置");
        }
        ChatModel model = OpenAiChatModel.builder().apiKey(properties.model().apiKey()).baseUrl(properties.model().baseUrl()).modelName(properties.model().modelName()).timeout(java.time.Duration.ofSeconds(properties.model().timeoutSeconds())).maxRetries(0).build();
        List<Content> contents = new ArrayList<>();
        contents.add(TextContent.from("请分析这些漫画抽样页面。只输出 JSON：{\"titleCandidate\":null,\"authorCandidate\":null,\"tags\":[],\"description\":\"\",\"warnings\":[]}。标签自由生成；作品名和作者只能作为候选，不确定就填 null。简介只描述所给抽样页面，不要声称总结了全书。不要输出 Markdown。"));
        for (SamplePage page : pages) {
            byte[] bytes = optimizedImage(page.path());
            contents.add(ImageContent.from(Image.builder().base64Data(Base64.getEncoder().encodeToString(bytes)).mimeType("image/jpeg").build()));
        }
        ChatResponse response = model.chat(UserMessage.from(contents));
        String text = response.aiMessage().text();
        JsonNode json = objectMapper.readTree(normalizeJson(text));
        return objectMapper.writeValueAsString(json);
    }

    static String normalizeJson(String modelText) {
        if (modelText == null || modelText.isBlank()) {
            throw new IllegalArgumentException("AI 返回内容为空");
        }
        String normalized = modelText.trim();
        if (normalized.startsWith("```")) {
            int firstLineEnd = normalized.indexOf('\n');
            int closingFence = normalized.lastIndexOf("```");
            if (firstLineEnd > 0 && closingFence > firstLineEnd) {
                normalized = normalized.substring(firstLineEnd + 1, closingFence).trim();
            }
        }
        int objectStart = normalized.indexOf('{');
        int objectEnd = normalized.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) {
            normalized = normalized.substring(objectStart, objectEnd + 1);
        }
        return normalized;
    }
    private byte[] optimizedImage(java.nio.file.Path path) throws IOException {
        BufferedImage original = ImageIO.read(path.toFile());
        if (original == null) {
            return Files.readAllBytes(path);
        }
        int longestEdge = Math.max(original.getWidth(), original.getHeight());
        double scale = longestEdge > MAX_IMAGE_EDGE ? (double) MAX_IMAGE_EDGE / longestEdge : 1.0;
        int width = Math.max(1, (int) Math.round(original.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(original.getHeight() * scale));
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resized.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(original, 0, 0, width, height, null);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(resized, "jpg", output);
        return output.toByteArray();
    }
}
