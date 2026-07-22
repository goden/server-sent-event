package com.gordon.learning.serversentevent.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

/**
 * 建立一個專屬的 Service，利用 Micrometer 的 Counter (計數器) 來記錄攔截次數與 Token 消耗量
 */
@Service
public class AgentMetricsService {

    private final Counter securityInterceptCounter;
    private final Counter tokenUsageCounter;
    private final Counter pipelineSuccessCounter;

    // 將 MeterRegistry 注入，註冊我們的自訂指標
    public AgentMetricsService(MeterRegistry registry) {
        this.securityInterceptCounter = Counter.builder("agent_security_intercepts_total")
                .description("Security Agent 成功攔截潛在漏洞的次數")
                .register(registry);

        this.tokenUsageCounter = Counter.builder("agent_llm_tokens_total")
                .description("LLM API 總共消耗的 Token 數量")
                .register(registry);

        this.pipelineSuccessCounter = Counter.builder("agent_pipeline_success_total")
                .description("成功生成測試與重構程式碼的次數")
                .register(registry);
    }

    // 觸發資安攔截
    public void recordSecurityIntercept() {
        securityInterceptCounter.increment();
    }

    // 紀錄 Token 使用量
    public void recordTokensUsed(int tokens) {
        tokenUsageCounter.increment(tokens);
    }

    // 紀錄管線成功
    public void recordPipelineSuccess() {
        pipelineSuccessCounter.increment();
    }

}
