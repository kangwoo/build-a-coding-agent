package agent.hook;

import agent.message.Json;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 실행기 자체의 경계 셋 — 타임아웃, exit 2의 stderr 우선, JSON+exit 2 조합. */
@DisabledOnOs(OS.WINDOWS)
class CommandHookExecutorTest {

    static boolean bash() {
        try { return new ProcessBuilder("bash", "-c", "true").start().waitFor() == 0; }
        catch (Exception e) { return false; }
    }

    @TempDir Path dir;
    private final CommandHookExecutor exec = new CommandHookExecutor();

    @Test @EnabledIf("bash")
    void timeout_kills_the_hook_as_non_blocking_error() {
        // 1초 타임아웃 vs sleep 30 → 강제 종료 후 '비차단 오류' — 고장 난 훅이 도구를 잠그면 안 된다
        var r = exec.execute(new HookCommand.Command("sleep 30", 1),
                Json.MAPPER.createObjectNode(), dir);
        assertThat(r.outcome()).isEqualTo(HookResult.Outcome.NON_BLOCKING_ERROR);
        assertThat(r.message()).hasValueSatisfying(m -> assertThat(m).contains("타임아웃"));
    }

    @Test @EnabledIf("bash")
    void exit_2_feedback_prefers_stderr_over_stdout() {
        // exit 2의 피드백은 stderr 우선 — stdout에 잡음이 있어도 stderr가 모델에 전달된다
        var r = exec.execute(
                new HookCommand.Command("echo noise; echo blocked 1>&2; exit 2", 10),
                Json.MAPPER.createObjectNode(), dir);
        assertThat(r.outcome()).isEqualTo(HookResult.Outcome.BLOCKING);
        assertThat(r.message()).hasValueSatisfying(m -> assertThat(m).contains("blocked"));
    }

    @Test @EnabledIf("bash")
    void json_stdout_with_exit_2_is_blocking_and_fields_are_parsed() {
        // stdout이 JSON이면 종료코드 분기가 아니라 파싱 경로 — exit 2와 조합되면 차단 + 필드 전달
        var cmd = "echo '{\"permissionDecision\":\"deny\",\"message\":\"금지된 경로\","
                + "\"additionalContext\":\"리포 규칙 위반\"}'; exit 2";
        var r = exec.execute(new HookCommand.Command(cmd, 10),
                Json.MAPPER.createObjectNode(), dir);
        assertThat(r.outcome()).isEqualTo(HookResult.Outcome.BLOCKING);
        assertThat(r.permissionDecision()).contains("deny");
        assertThat(r.message()).contains("금지된 경로");
        assertThat(r.additionalContext()).contains("리포 규칙 위반");
    }
}
