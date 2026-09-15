package com.fanzhuo.mcpserver;

import com.fanzhuo.mcpserver.tool.ImageSearchTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Image Search MCP Server
 * <p>
 * 通过 WebMVC + SSE 传输暴露 ImageSearchTool 为 MCP 工具，
 * 供任意 MCP Client（如 Claude Desktop、Cherry Studio、其他 Spring AI 应用）远程调用。
 */
@SpringBootApplication
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }

    /**
     * 将 ImageSearchTool 注册为 MCP 工具回调提供者。
     * Spring AI MCP 自动配置会自动捕获 ToolCallbackProvider 并注册为 MCP tool。
     */
    @Bean
    public ToolCallbackProvider imageSearchToolCallbackProvider(ImageSearchTool imageSearchTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(imageSearchTool)
                .build();
    }
}
