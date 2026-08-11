package agent.warmup;

import java.util.List;
import java.util.Map;

public final class Main {

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("환경 변수 OPENAI_API_KEY 가 설정되지 않았습니다. (부록 A.4)");
            System.exit(1);
        }

        // 인자로 프롬프트를 주면 그걸, 없으면 기본 질문을 보낸다.
        String prompt = args.length > 0
                ? String.join(" ", args)
                : "자바 21의 가상 스레드를 한 문장으로 설명해줘.";

        OpenAiChat client = new OpenAiChat(apiKey);

        // 메시지 = system(행동 규칙) + user(질문)
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", "너는 간결하게 답하는 자바 도우미다."),
                Map.of("role", "user", "content", prompt)
        );

        String answer = client.chat(OpenAiChat.DEFAULT_MODEL, messages);
        System.out.println("AI: " + answer);
    }
}
