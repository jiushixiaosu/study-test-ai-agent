package com.fanzhuo.quickstart.web.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

// 已改为 MVC 模式（见 ChatController）。停用 CommandLineRunner 自动执行。
//@Component
public class ImageSearchDemoRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ImageSearchDemoRunner.class);
    private final ChatClient chatClient;

    public ImageSearchDemoRunner(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public void run(String... args) {
        System.out.println("===== AI 记忆对话演示 =====");

        String response = chatClient.prompt()
                .user("我想知道厦门18点左右的天气情况")
                .advisors(a -> a.param("chat_memory_conversation_id", "demo-session"))
                .advisors(new SimpleLoggerAdvisor())
                .call()
                .content();

        System.out.println("AI 回答: " + response);

        /*String response2 = chatClient.prompt()
                .user("请问我是谁")
                .advisors(a -> a.param("chat_memory_conversation_id", "demo-session2"))
                .call()
                .content();

        System.out.println("AI 回答: " + response2);*/
    }

    /**
     * ai基础对话
     * @param message
     * @param chatId
     * @return
     */
    public String doChat(String message, String chatId){

        ChatResponse chatResponse = chatClient.prompt()
                .user(message)
                .advisors(a -> a.param("chat_memory_conversation_id", chatId))
                .call()
                .chatResponse();

        log.info("content:{}", chatResponse.getResult().getOutput().getText());
        return chatResponse.getResult().getOutput().getText();
    }
}
