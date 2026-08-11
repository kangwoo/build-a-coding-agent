package agent.llm;

import com.fasterxml.jackson.databind.JsonNode;

/** 모델에게 알려줄 도구 1개의 명세. 8장에서 입력 스키마 생성과 연결된다. */
public record ToolSpec(String name, String description, JsonNode inputSchema) {}
