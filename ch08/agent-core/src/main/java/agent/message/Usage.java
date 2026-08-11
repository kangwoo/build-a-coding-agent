package agent.message;

public record Usage(
        long inputTokens,
        long outputTokens,
        long cacheReadInputTokens,
        long cacheCreationInputTokens,
        int webSearchRequests) {

    public static final Usage EMPTY = new Usage(0, 0, 0, 0, 0);

    /**
     * 스트리밍 중 새 usage 스냅샷을 반영한다.
     * API는 매번 "현재까지 누적 총량"을 보내며, 어떤 필드를 0으로 보내기도 한다.
     * 0은 '값 없음'이지 '0으로 리셋'이 아니므로, += 가 아니라 "0보다 크면 교체"로 병합한다.
     */
    public Usage mergeCumulative(Usage next) {
        return new Usage(
            next.inputTokens > 0 ? next.inputTokens : inputTokens,
            next.outputTokens > 0 ? next.outputTokens : outputTokens,
            next.cacheReadInputTokens > 0 ? next.cacheReadInputTokens : cacheReadInputTokens,
            next.cacheCreationInputTokens > 0 ? next.cacheCreationInputTokens : cacheCreationInputTokens,
            next.webSearchRequests > 0 ? next.webSearchRequests : webSearchRequests
        );
    }

    public long totalTokens() {
        return inputTokens + outputTokens + cacheReadInputTokens + cacheCreationInputTokens;
    }
}
