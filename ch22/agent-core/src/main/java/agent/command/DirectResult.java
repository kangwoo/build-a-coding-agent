package agent.command;

public sealed interface DirectResult
        permits DirectResult.Text, DirectResult.Skip, DirectResult.ClearHistory, DirectResult.Exit {
    record Text(String text) implements DirectResult {}      // 화면에 출력
    record Skip() implements DirectResult {}                  // 아무것도 안 함
    record ClearHistory() implements DirectResult {}          // 대화 비우기
    record Exit() implements DirectResult {}                  // 종료 요청(처리는 임베더 몫)
}
