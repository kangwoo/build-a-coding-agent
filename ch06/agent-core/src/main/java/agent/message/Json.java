package agent.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public final class Json {
    private Json() {}

    /** 공유 ObjectMapper — 가변 객체이므로 설정은 이 빌더 체인에서 끝낸다(이후 재설정 금지 — 한 곳의 변경이 전역에 퍼진다). */
    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())                              // Instant 지원
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)      // ISO-8601 문자열로
            .serializationInclusion(JsonInclude.Include.NON_NULL)        // null 필드 생략
            .build();

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("JSON 직렬화 실패", e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new RuntimeException("JSON 역직렬화 실패", e);
        }
    }
}
