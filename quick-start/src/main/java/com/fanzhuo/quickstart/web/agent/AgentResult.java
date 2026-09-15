package com.fanzhuo.quickstart.web.agent;

import java.util.List;

/**
 * Agent 单次运行的完整结果（含可见的决策步骤）。
 *
 * @param answer    最终答案；未完成时为 null
 * @param steps     过程中每一次工具调用（供前端展示"思考过程"）
 * @param stepsUsed 实际消耗的步数
 * @param completed 是否在步数上限内正常完成
 * @param maxSteps  配置的步数上限
 * @param note      附加说明（如"达到步数上限"、"会话处理中"）
 */
public record AgentResult(
        String answer,
        List<AgentStep> steps,
        int stepsUsed,
        boolean completed,
        int maxSteps,
        String note) {

    /** 正常完成 */
    public static AgentResult done(String answer, List<AgentStep> steps, int maxSteps) {
        return new AgentResult(answer, steps, steps.size(), true, maxSteps, null);
    }

    /** 达到步数上限仍未收敛 */
    public static AgentResult limitReached(String lastText, List<AgentStep> steps, int maxSteps) {
        String tip = "已达到最大步数 " + maxSteps + "，任务可能未完全完成。";
        String answer = (lastText == null || lastText.isBlank()) ? tip : lastText + "\n\n（" + tip + "）";
        return new AgentResult(answer, steps, steps.size(), false, maxSteps, tip);
    }

    /** 同一会话上一条还在处理中（未抢到分布式锁） */
    public static AgentResult busy(int maxSteps) {
        return new AgentResult("上一条消息还在处理中，请稍候再提问~",
                List.of(), 0, false, maxSteps, "busy");
    }

    /** 执行异常 */
    public static AgentResult failed(String message, List<AgentStep> steps, int maxSteps) {
        return new AgentResult("处理失败：" + message, steps, steps.size(), false, maxSteps, "error");
    }

    /**
     * 一次工具调用记录。
     *
     * @param step      第几步
     * @param toolName  工具名
     * @param arguments 模型传入的 JSON 参数
     */
    public record AgentStep(int step, String toolName, String arguments) {
    }
}
