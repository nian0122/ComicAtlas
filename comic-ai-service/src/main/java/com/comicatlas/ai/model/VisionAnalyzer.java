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
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.stereotype.Service;

/** LangChain4j 视觉模型适配器。业务层只接收 JSON 文本。 */
@Service
public class VisionAnalyzer {
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
            byte[] bytes = Files.readAllBytes(page.path());
            String mimeType = Files.probeContentType(page.path());
            contents.add(ImageContent.from(Image.builder().base64Data(Base64.getEncoder().encodeToString(bytes)).mimeType(mimeType == null ? "image/jpeg" : mimeType).build()));
        }
        ChatResponse response = model.chat(UserMessage.from(contents));
        String text = response.aiMessage().text();
        JsonNode json = objectMapper.readTree(text);
        return objectMapper.writeValueAsString(json);
    }
}
