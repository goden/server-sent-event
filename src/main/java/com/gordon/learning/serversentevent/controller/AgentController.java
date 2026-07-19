package com.gordon.learning.serversentevent.controller;

import com.gordon.learning.serversentevent.service.LlmStreamService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/agent")
@CrossOrigin(origins = "*") // 允許 VS Code 擴充套件跨域呼叫
public class AgentController {

    @Autowired
    private LlmStreamService service;

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAgentResponse(@RequestBody Map<String, String> requestData) {

        // 取得 VS Code 傳過來的上下文
        String userQuery = requestData.getOrDefault("query", "無問題");
        String codeContext = requestData.getOrDefault("code", "無程式碼");

        // 建立 SseEmitter，設定超時時間為 3 分鐘 (180000ms)
        SseEmitter emitter = new SseEmitter(180000L);

        // 將任務交給 Service 處理
        service.streamToClient(userQuery, codeContext, emitter);

        return emitter;

    }
}
