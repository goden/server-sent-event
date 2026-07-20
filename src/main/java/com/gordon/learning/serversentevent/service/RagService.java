package com.gordon.learning.serversentevent.service;

import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class RagService {

    // 模擬記憶體中的知識庫快取
    private final Map<String, String> knowledgeBase = new HashMap<>();

    /**
     * Spring Boot 啟動時，自動把內部的 Markdown 規範載入記憶體。
     * 這樣每次 Agent 查詢時都不用重新讀取硬碟，速度極快。
     */
    public void initKnowledgeBase() {
        // 實務上，把這些檔案放在 src/main/resources/docs 底下
        // 為求實作快速，直接用字串模擬載入的本文內容
        knowledgeBase.put("JAVA_SECURITY",
                "【後端資安規範】：\n" +
                        "1. 所有的 Controller 方法必須加上 @RequiresPermissions 檢查權限。\n" +
                        "2. 不允許使用字串拼接 SQL，必須使用 Prepared Statement 或 JPA。\n" +
                        "3. 捕捉到 Exception 時，絕對不能使用 e.printStackTrace()，必須使用 logger.error()。");

        knowledgeBase.put("ANGULAR_21_UPGRADE",
                "【前端 Angular 21 規範】：\n" +
                        "1. 必須使用 Standalone Components，不允許再宣告 NgModule。\n" +
                        "2. 所有的 API 呼叫必須透過自製的 BankHttpInterceptor。\n" +
                        "3. Playwright 測試中，定位元素請優先使用 page.getByTestId()，禁止使用 CSS class 定位。");

        knowledgeBase.put("JUNIT_TESTING",
                "【單元測試規範】：\n" +
                        "1. JUnit 5 測試類別名稱必須以 Test 結尾。\n" +
                        "2. 必須使用 Mockito.mock() 隔離外部依賴，禁止在單元測試連線真實資料庫。");
    }

    /**
     * 根據 VS Code 傳來的程式碼內容，動態挑選出相關的內部規範
     */
    public String findRelevantGuidelines(String codeContent) {
        if (codeContent == null || codeContent.trim().isEmpty()) {
            return "無特別規範。";
        }

        StringBuilder relevantDocs = new StringBuilder();
        String codeLower = codeContent.toLowerCase();

        // 簡單而暴力的關鍵字路由 (Keyword-based Routing)
        if (codeLower.contains("@restcontroller") || codeLower.contains("@service") || codeLower.contains("java")) {
            relevantDocs.append(knowledgeBase.get("JAVA_SECURITY")).append("\n\n");
            relevantDocs.append(knowledgeBase.get("JUNIT_TESTING")).append("\n\n");
        }

        if (codeLower.contains("@component") || codeLower.contains("implements oninit") || codeLower.contains(".ts")) {
            relevantDocs.append(knowledgeBase.get("ANGULAR_21_UPGRADE")).append("\n\n");
        }

        // 如果都沒對中，就回傳空字串
        return !relevantDocs.isEmpty() ? relevantDocs.toString() : "無匹配的內部規範。";
    }

}
