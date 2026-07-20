package com.gordon.learning.serversentevent.service;

import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
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

    // 1. 注入我們剛剛寫好的 AST 服務
    @Autowired
    private JavaAstService javaAstService;

    public LlmStreamService() {
        this.client = new OkHttpClient.Builder()
                .readTimeout(3, TimeUnit.MINUTES) // LLM 思考時間較長，拉長 Timeout
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Deprecated
    public void streamToClient(String userQuery, String codeContext, SseEmitter emitter) {

        try {

            // 2. 在背景執行 JavaParser 語法樹分析
            String astAnalysisResult =  javaAstService.analyzeStructure(codeContext);

            // 3. 升級 System Prompt，迫使 AI 必須對齊後端分析出來的骨架
            String systemPrompt = "你是一位精通 Java 8 與自動化測試的頂尖架構師。\n" +
                    "後端系統已經為你解析了該檔案的精確 AST 結構如下：\n" +
                    astAnalysisResult + "\n" +
                    "請嚴格根據上方分析出的公開方法與異常聲明，為使用者規劃完整的 JUnit 5 測試案例。" +
                    "請使用專業的繁體中文回答。";

            String userMessage = "使用者具體提問：" + userQuery + "\n\n原始碼全文：\n" + codeContext;

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

                // 新增一個 StringBuilder，用來收集完整的 LLM 回覆
                private final StringBuilder fullResponseBuilder = new StringBuilder();

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

                            String fullText = fullResponseBuilder.toString();

                            // 簡易萃取邏輯：抓取 Markdown 中的 java 程式碼區塊
                            int startIndex = fullText.indexOf("```java");
                            int endIndex = fullText.indexOf("```");

                            if (startIndex != -1 && endIndex > startIndex) {
                                // 將純程式碼提取出來
                                String javaCode =  fullText.substring(startIndex + 7, endIndex);

                                // 建立要傳給VS Code的執行指令(Action)
                                ObjectNode actionNode = objectMapper.createObjectNode();
                                actionNode.put("action", "create_file");

                                // 實務層面看可以透過LLM或JavaParser動態決定檔名，此處就先固定檔名
                                actionNode.put("fileName", "CodeGuardianGeneratedTest.java");
                                actionNode.put("content", javaCode);

                                // 發送自訂的 action 事件
                                emitter.send(SseEmitter.event().name("action").data(actionNode.toString()));

                            }

                            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                            emitter.complete();

                        } catch (IOException e) {
                            System.err.println(e.getMessage());
                        }
                        return;
                    }

                    // 處理串流中的文字，同步紀錄到 StringBuilder中


                    try {
                        // 解析 OpenAI 格式的回傳值 {"choices": [{"delta": {"content": "這"}}]}
                        JsonNode jsonNode = objectMapper.readTree(data);
                        JsonNode choices = jsonNode.path("choices");
                        if (choices.isArray() && !choices.isEmpty()) {
                            JsonNode contentNode = choices.get(0).path("delta").path("content");
                            if (!contentNode.isMissingNode()) {

                                String textChunk = contentNode.asString();

                                // 收集文字
                                fullResponseBuilder.append(textChunk);

                                // 轉義換行並推送給 VS Code 介面
                                String safeChunk = textChunk.replace("\n", "\\n");
                                emitter.send(SseEmitter.event().name("message").data(safeChunk));
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("解析 LLM 回應失敗: " + e.getMessage());
                    }
                }

                @Override
                public void onFailure(@NonNull EventSource eventSource, @Nullable Throwable t, @Nullable Response response) {
                    try {
                        assert t != null;
                        emitter.send(SseEmitter.event().name("message").data("\\n\\n❌ LLM 呼叫失敗: " + t.getMessage()));
                        emitter.completeWithError(t);
                    } catch (IOException e) {
                        System.err.println(e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            emitter.completeWithError(e);
        }

    }

    /**
     * 1. 同步呼叫 (Sync)：用於 Security Agent
     * 阻斷執行緒，直到 LLM 完整回覆為止。
     */
    public String callLlmSync(String systemPrompt, String codeContext) throws IOException {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", modelName);
        payload.put("stream", false); // 關閉串流

        ArrayNode messages = payload.putArray("messages");
        messages.add(objectMapper.createObjectNode().put("role", "system").put("content", systemPrompt));
        messages.add(objectMapper.createObjectNode().put("role", "user").put("content", codeContext));

        RequestBody body = RequestBody.create(payload.toString(), MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(apiUrl)
                .post(body)
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();

        // 執行同步請求 (execute 會卡住直到有回應)
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("LLM 呼叫失敗: " + response);
            }
            // 解析回傳的整包 JSON
            JsonNode rootNode = objectMapper.readTree(response.body().string());
            return rootNode.path("choices").get(0).path("message").path("content").asString();
        }
    }

    /**
     * 2. 串流呼叫與動作萃取 (Stream & Action)：用於 QA Agent
     * 使用 SSE 一字一字回傳，並在結束時擷取 Java 程式碼觸發建檔。
     * 這其實就是我們上一堂課 LlmStreamService 的進化版。
     */
    public void callLlmStreamAndExtractAction(String systemPrompt, String codeContext, SseEmitter emitter) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("model", modelName);
            payload.put("stream", true); // 開啟串流

            ArrayNode messages = payload.putArray("messages");
            messages.add(objectMapper.createObjectNode().put("role", "system").put("content", systemPrompt));
            messages.add(objectMapper.createObjectNode().put("role", "user").put("content", codeContext));

            RequestBody body = RequestBody.create(payload.toString(), MediaType.get("application/json; charset=utf-8"));
            Request request = new Request.Builder()
                    .url(apiUrl)
                    .post(body)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Accept", "text/event-stream")
                    .build();

            EventSource.Factory factory = EventSources.createFactory(client);
            factory.newEventSource(request, new EventSourceListener() {
                private final StringBuilder fullResponseBuilder = new StringBuilder();

                @Override
                public void onEvent(@NonNull EventSource eventSource, String id, String type, @NonNull String data) {
                    if ("[DONE]".equals(data)) {
                        try {
                            String fullText = fullResponseBuilder.toString();
                            int startIndex = fullText.indexOf("```java");
                            int endIndex = fullText.lastIndexOf("```");

                            // 擷取程式碼並觸發 VS Code 自動建檔 (Action)
                            if (startIndex != -1 && endIndex > startIndex) {
                                String javaCode = fullText.substring(startIndex + 7, endIndex).trim();
                                ObjectNode actionNode = objectMapper.createObjectNode();
                                actionNode.put("action", "create_file");
                                actionNode.put("fileName", "CodeGuardianGeneratedTest.java");
                                actionNode.put("content", javaCode);
                                emitter.send(SseEmitter.event().name("action").data(actionNode.toString()));
                            }

                            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                            emitter.complete();
                        } catch (IOException e) {
                            System.err.println(e.getMessage());
                        }
                        return;
                    }

                    // 處理文字串流
                    try {
                        JsonNode jsonNode = objectMapper.readTree(data);
                        JsonNode contentNode = jsonNode.path("choices").get(0).path("delta").path("content");
                        if (!contentNode.isMissingNode()) {
                            String textChunk = contentNode.asString();
                            fullResponseBuilder.append(textChunk);
                            emitter.send(SseEmitter.event().name("message").data(textChunk.replace("\n", "\\n")));
                        }
                    } catch (Exception e) {
                        System.err.println(e.getMessage());
                    }
                }

                @Override
                public void onFailure(@NonNull EventSource eventSource, Throwable t, Response response) {
                    try {
                        emitter.completeWithError(t);
                    } catch (Exception e) {
                        System.err.println(e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }
}
