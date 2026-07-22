package com.gordon.learning.serversentevent.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@Service
public class MultiAgentPipelineService {

    @Autowired
    private LlmStreamService llmClient;

    @Autowired
    private RagService ragService;

    @Autowired
    private JavaAstService astService;
    @Autowired
    private AgentMetricsService agentMetricsService;

    public void executePipeline(String userQuery, String codeContext, SseEmitter emitter) throws IOException {

        try {
            // ==========================================
            // 階段一：準備上下文 (RAG 與 AST)
            // ==========================================

            // 1. RAG 檢索：找出公司內部的測試與資安規範
            emitter.send(SseEmitter.event().name("progress").data("正在檢索內部架構規範..."));
            String companyGuidelines = ragService.findRelevantGuidelines(codeContext);

            // 2. 語法樹解析
            emitter.send(SseEmitter.event().name("progress").data("🌳 正在解析 JavaParser 抽象語法樹..."));
            String astContext = astService.analyzeStructure(codeContext);

            // ==========================================
            // 階段二：第一棒 - Security Agent (資安審查)
            // ==========================================

            // 1. 第一棒：Security Agent (專注於找漏洞，不寫測試)
            emitter.send(SseEmitter.event().name("progress").data("資安 Agent 正在進行漏洞掃描..."));
            String securityPrompt = """
                    你是一位金融業資安專家。請根據以下公司規範檢查程式碼，是否有 SQL Injection 或權限控管漏洞？若有，請指出。若無，請回答「安全」。
                    內部規範：%s""".formatted(companyGuidelines);

            // 這裡必須「同步」等待資安專家的檢查結果
            String securityReport = llmClient.callLlmSync(securityPrompt, codeContext); // 這裡是同步等待結果

            // 攔截機制：如果資安專家沒有給出「【安全】」的綠燈，直接退回，中斷管線
            if (!securityReport.contains("【安全】")) {

                // 紀錄一次成功的資安攔截！
                agentMetricsService.recordSecurityIntercept();

                String warningMessage = "⚠️ **Security Agent 阻擋了此次提交**：\n\n" + securityReport;
                emitter.send(SseEmitter.event().name("progress").data(warningMessage));

                // 將換行符號轉義，以便透過 SSE 傳送
                emitter.send(SseEmitter.event().name("message").data("**資安掃描未通過**：\\n" + securityReport.replace("\n", "\\n")));
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
                return;
            }

            // ==========================================
            // 階段三：第二棒 - QA Agent (測試生成與建檔)
            // ==========================================

            emitter.send(SseEmitter.event().name("progress").data("✅ 資安審查通過。QA Agent 正在設計測試案例..."));

            // 1. 第二棒：QA Agent (拿著資安報告、AST 與 RAG 規範來寫扣)
            String qaPrompt = """
                    你是一位頂尖的自動化測試架構師。此程式碼已通過資安審查。
                    請根據使用者的需求、企業內部規範，以及系統解析出的 AST 結構，撰寫最嚴謹的測試程式碼 (優先使用 JUnit 5 或 Playwright)。
                    請直接給出包含 Markdown 標記 (如 ```java) 的完整測試程式碼，不要有過多的廢話。

                    【使用者需求】
                    %s

                    【AST 語法樹結構】
                    %s

                    【公司內部規範】
                    %s""".formatted(userQuery, astContext, companyGuidelines);

            // 2. 呼叫串流 API，把 QA 寫出來的程式碼「即時打字」傳回 VS Code
            llmClient.callLlmStreamAndExtractAction(qaPrompt, codeContext, emitter);

            // 管線順利走完，紀錄一次成功產出
            agentMetricsService.recordPipelineSuccess();

        } catch (IOException e) {
            try {
                emitter.send(SseEmitter.event().name("message").data("\\n\\n❌ 系統管線執行失敗：" + e.getMessage()));
                emitter.completeWithError(e);
            } catch (IOException ex) {
                // 忽略網路斷線
            }
        }
    }

}
