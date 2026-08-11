package agent.llm;

/**
 * 시스템 프롬프트의 provider 중립 블록. dynamic=true면 세션·요청마다 바뀔 수 있는 동적 섹션이다
 * (18장 정적 → 동적 순서에서 캐시 경계 뒤). 회사별 캐시 마커(cache_control 등)는 여기 없다 —
 * 경계를 어떻게 살릴지는 각 LlmClient 구현이 정한다(5장 capabilities 분기).
 */
public record SystemBlock(String text, boolean dynamic) {

    public static SystemBlock staticBlock(String text) {
        return new SystemBlock(text, false);
    }

    public static SystemBlock dynamicBlock(String text) {
        return new SystemBlock(text, true);
    }
}
