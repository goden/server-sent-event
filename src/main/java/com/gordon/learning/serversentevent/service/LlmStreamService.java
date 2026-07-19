package com.gordon.learning.serversentevent.service;

import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Service
public class LlmStreamService {

    @Value("${llm.api.key}")
    private String apiKey;

    @Value("${llm.api.url}")
    private String apiUrl;

    @Value("${llm.api.model}")
    private String modelName;

    private final OkHttpClient client;
    private final ObjectMapper objectMapper;

    public LlmStreamService() {
        this.client = new OkHttpClient.Builder()
                .readTimeout(3, TimeUnit.MINUTES) // LLM 思考時間較長，拉長 Timeout
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public void streamToClient(String userQuery, String codeContext, SseEmitter emitter) {

        try {
            // 1. 構建 System Prompt (賦予 Agent 專家角色與銀行規範)
            String systemPrompt = "你是一位精通 Java 8 與 Angular 的銀行資深 IT 架構師。" +
                    "請針對使用者提供的程式碼與問題，給出最嚴謹的重構建議或自動化測試腳本。" +
                    "必須符合企業級 Clean Code 原則。";

            String userMessage = "問題：" + userQuery + "\n\n程式碼上下文：\n" + codeContext;

            // 2. 構建 JSON Payload
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("model", modelName);
            payload.put("stream", true); // 關鍵：要求 LLM 開啟串流模式

            ArrayNode messages = payload.putArray("messages");

            ObjectNode sysNode = objectMapper.createObjectNode();
            sysNode.put("role", "system");
            sysNode.put("content", systemPrompt);
            messages.add(sysNode);

            ObjectNode userNode = objectMapper.createObjectNode();
            userNode.put("role", "user");
            userNode.put("content", userMessage);
            messages.add(userNode);

            RequestBody body = RequestBody.create(
                    payload.toString(),
                    MediaType.get("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url(apiUrl)
                    .post(body)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Accept", "text/event-stream")
                    .build();

            // 3. 發送請求並設定串流監聽器
            EventSource.Factory factory = EventSources.createFactory(client);
            factory.newEventSource(request, new EventSourceListener() {
                @Override
                public void onOpen(@NonNull EventSource eventSource, @NonNull Response response) {
                    try {
                        emitter.send(SseEmitter.event().name("progress").data("大腦已連線，正生成解答…"));
                    } catch (IOException e) {
                        // 忽略client端斷線
                    }
                }

                @Override
                public void onEvent(@NonNull EventSource eventSource, @Nullable String id, @Nullable String type, @NonNull String data) {
                    if (data.equals("[DONE]")) {
                        try {
                            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                            emitter.complete();
                        } catch (IOException e) {}
                        return;
                    }

                    try {
                        // 解析 OpenAI 格式的回傳值 {"choices": [{"delta": {"content": "這"}}]}
                        JsonNode jsonNode = objectMapper.readTree(data);
                        JsonNode choices = jsonNode.path("choices");
                        if (choices.isArray() && !choices.isEmpty()) {
                            JsonNode contentNode = choices.get(0).path("delta").path("content");
                            if (!contentNode.isMissingNode()) {
                                String textChunk = contentNode.asString();
                                // 將換行符號轉義，避免打斷 SSE 格式
                                textChunk = textChunk.replace("\n", "\\n");
                                emitter.send(SseEmitter.event().name("message").data(textChunk));
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("解析 LLM 回應失敗: " + e.getMessage());
                    }
                }

                @Override
                public void onFailure(@NonNull EventSource eventSource, @Nullable Throwable t, @Nullable Response response) {
                    try {
                        emitter.send(SseEmitter.event().name("message").data("\\n\\n❌ LLM 呼叫失敗: " + t.getMessage()));
                        emitter.completeWithError(t);
                    } catch (IOException e) {}
                }
            });
        } catch (Exception e) {
            emitter.completeWithError(e);
        }

    }
}
