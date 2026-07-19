package com.gordon.learning.serversentevent.service;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.springframework.stereotype.Service;

@Service
public class JavaAsService {

    public String analyzeStructure(String code) {

        if (code == null || code.trim().isEmpty()) {
            return "【無可分析的程式碼內容】";
        }

        try {

            // 1. 將純文字原始碼解析為 CompilationUnit (AST 根節點)
            CompilationUnit cu = StaticJavaParser.parse(code);
            StringBuilder sb = new StringBuilder();

            sb.append("=== JavaParser 本地原始碼結構分析 ===\n");

            // 2. 尋找程式碼中所有的 Class 或 Interface
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
                String type = clazz.isInterface() ? "介面" : "類別";
                sb.append(type).append(": ").append(clazz.getNameAsString()).append("\n");

                // 3. 深入尋找該類別底下的所有方法 (Method)
                clazz.findAll(MethodDeclaration.class).forEach((method) -> {
                    // 只抓 public 方法來設計 JUnit 5 測試，忽略 private 輔助邏輯
                    if (method.isPublic()) {
                        sb.append("[Public Method]: ").append(method.getNameAsString()).append("\n");

                        // 進階偵測：如果方法有拋出異常，特別記錄下來提醒 AI 寫測試
                        if (!method.getThrownExceptions().isEmpty()) {
                            sb.append("     【注意】X`X`：此方法聲明拋出異常: ").append(method.getThrownExceptions()).append("\n");
                        }
                    }
                });
            });
            return sb.toString();
        } catch (Exception e) {
            return "【】";
        }
    }
}
