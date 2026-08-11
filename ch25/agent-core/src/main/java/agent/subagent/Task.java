package agent.subagent;

/** 비동기 작업의 생명주기 모델. 상태 enum + 불변 State record(23.3.5 비동기 골격에서 추적용). */
public final class Task {
    public enum Status { PENDING, RUNNING, COMPLETED, FAILED, KILLED }

    public record State(String id, Status status, String description, String result) {
        public boolean isTerminal() {
            return status == Status.COMPLETED || status == Status.FAILED || status == Status.KILLED;
        }
        public State withStatus(Status s) { return new State(id, s, description, result); }
        public State withResult(String r) { return new State(id, Status.COMPLETED, description, r); }
    }
}
