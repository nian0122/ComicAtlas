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
    private static final int BATCH_SIZE = 6;
    public String analyze(List<SamplePage> pages, List<String> existingTags) throws IOException {
        if (properties.model().apiKey() == null || properties.model().apiKey().isBlank() || properties.model().modelName() == null || properties.model().modelName().isBlank()) {
            throw new IllegalStateException("AI_API_KEY 和 AI_MODEL 必须配置");
        }
        ChatModel model = OpenAiChatModel.builder().apiKey(properties.model().apiKey()).baseUrl(properties.model().baseUrl()).modelName(properties.model().modelName()).timeout(java.time.Duration.ofSeconds(properties.model().timeoutSeconds())).maxRetries(0).build();
        List<String> batchResults = new ArrayList<>();
        for (int start = 0; start < pages.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, pages.size());
            batchResults.add(analyzeBatch(model, pages.subList(start, end), existingTags));
        }
        String text = summarize(model, batchResults, existingTags);
        JsonNode json = parseModelJson(model, text);
        return objectMapper.writeValueAsString(json);
    }

    private String analyzeBatch(ChatModel model, List<SamplePage> pages, List<String> existingTags) throws IOException {
        List<Content> contents = new ArrayList<>();
        contents.add(TextContent.from(batchPrompt(existingTags)));
        for (SamplePage page : pages) {
            byte[] bytes = optimizedImage(page.path());
            contents.add(ImageContent.from(Image.builder().base64Data(Base64.getEncoder().encodeToString(bytes)).mimeType("image/jpeg").build()));
        }
        ChatResponse response = model.chat(UserMessage.from(contents));
        return objectMapper.writeValueAsString(parseModelJson(model, response.aiMessage().text()));
    }

    private String summarize(ChatModel model, List<String> batchResults, List<String> existingTags) {
        String prompt = "请根据以下漫画分批分析结果，生成最终分类结果。只输出 JSON："
                + "{\"titleCandidate\":null,\"authorCandidate\":null,\"tags\":[],\"description\":\"\",\"warnings\":[]}。"
                + "标签优先从现有标签列表中选择，必须保持现有标签原文；只有没有语义匹配时才允许新增标签。"
                + "合并同义词、删除重复标签；标签必须是简短名词或短语。只保留至少在两个批次出现，或在一个批次中有明确证据的标签。"
                + "标题和作者不确定时填 null，简介只能概括抽样结果，不要臆测全书。响应第一个字符必须是 {，最后一个字符必须是 }，不要输出 Markdown 或任何说明文字。"
                + "现有标签：" + formatTags(existingTags) + "。分批结果：" + String.join("\n", batchResults);
        return model.chat(UserMessage.from(TextContent.from(prompt))).aiMessage().text();
    }

    private String batchPrompt(List<String> existingTags) {
        return "请分析这些漫画抽样页面，只输出 JSON：{\"tags\":[],\"pageEvidence\":{},\"pageSummary\":\"\"}。"
                + "只根据图片中明确可见内容判断，不确定不要猜测。标签优先使用现有标签原文，没有匹配时才提出新标签。"
                + "pageEvidence 用标签到页码数组的映射，pageSummary 只描述本批页面。不要输出 Markdown。"
                + "现有标签：" + formatTags(existingTags);
    }

    private String formatTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "[]";
        }
        return objectMapper.valueToTree(tags).toString();
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
        if (objectStart >= 0) {
            int depth = 0;
            boolean insideString = false;
            boolean escaped = false;
            for (int index = objectStart; index < normalized.length(); index++) {
                char current = normalized.charAt(index);
                if (insideString) {
                    if (escaped) {
                        escaped = false;
                    } else if (current == '\\') {
                        escaped = true;
                    } else if (current == '"') {
                        insideString = false;
                    }
                } else if (current == '"') {
                    insideString = true;
                } else if (current == '{') {
                    depth++;
                } else if (current == '}' && --depth == 0) {
                    return normalized.substring(objectStart, index + 1).trim();
                }
            }
        }
        return normalized;
    }

    private JsonNode parseModelJson(ChatModel model, String modelText) throws IOException {
        String normalized = normalizeJson(modelText);
        try {
            return objectMapper.readTree(normalized);
        } catch (IOException parseException) {
            String repairPrompt = "请把下面模型输出修复为合法 JSON 对象。只输出 JSON，不要解释，不要 Markdown。"
                    + "必须包含对象结构，保留原有信息；无法确认的值使用 null 或空数组。原始输出：" + modelText;
            String repaired = model.chat(UserMessage.from(TextContent.from(repairPrompt))).aiMessage().text();
            try {
                return objectMapper.readTree(normalizeJson(repaired));
            } catch (IOException repairException) {
                repairException.addSuppressed(parseException);
                throw repairException;
            }
        }
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
