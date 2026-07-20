package com.gordon.learning.serversentevent.controller;

import com.gordon.learning.serversentevent.service.MultiAgentPipelineService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/agent")
@CrossOrigin(origins = "*") // 允許 VS Code 擴充套件跨域呼叫
public class AgentController {

    @Autowired
    private MultiAgentPipelineService pipelineService;

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAgentResponse(@RequestBody Map<String, String> requestData) {

        String userQuery = requestData.getOrDefault("query", "請協助生成測試");
        String codeContext = requestData.getOrDefault("code", "");

        // 建立 SseEmitter，超時設為 3 分鐘 (因為多智能體接力需要較長的思考時間)
        SseEmitter emitter = new SseEmitter(180000L);

        // 啟動多智能體流水線 (背景執行避免阻塞 HTTP 執行緒)
        new Thread(() -> {
            try {
                pipelineService.executePipeline(userQuery, codeContext, emitter);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).start();

        return emitter;

    }
}
