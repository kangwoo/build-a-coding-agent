package agent.tool.builtin;

import agent.message.ContentBlock.ToolResultBlock;
import agent.tool.*;
import agent.tool.schema.Desc;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 가장 강력하고 위험한 도구. 셸 명령을 실행하고 합쳐진 출력(merged fd)을 캡처하며,
 * 타임아웃·tree-kill·종료코드 해석·취소(14장)를 다룬다. 권한은 16장에서 붙는다.
 */
public final class BashTool implements Tool<BashTool.Input, BashTool.Output> {

    private static final long DEFAULT_TIMEOUT_MS = 120_000;   // 2분
    private static final long MAX_TIMEOUT_MS = 600_000;       // 10분
    private static final int MAX_OUTPUT = 30_000;             // 인라인 출력 상한(자)

    public record Input(@Desc("실행할 셸 명령") String command,
                        @Desc("타임아웃(ms, 최대 600000)") Optional<Long> timeoutMs,
                        @Desc("이 명령이 하는 일 한 줄 설명") Optional<String> description) {}

    /** 실행 결과. command를 함께 실어 mapResult가 명령별 종료코드 해석을 할 수 있게 한다. */
    public record Output(String command, String output, int exitCode,
                         boolean interrupted, boolean timedOut) {}

    @Override public String name() { return "Bash"; }
    @Override public String description() {
        return "셸 명령을 실행하고 합쳐진 출력(stdout+stderr)을 반환한다.";
    }
    @Override public Class<Input> inputType() { return Input.class; }
    // 위험 도구: 읽기전용 아님 + 동시 실행 안전 아님(직렬). 권한은 16장에서.
    @Override public boolean isDestructive(Input in) { return true; }

    @Override
    public ToolResult<Output> call(Input in, ToolContext ctx) throws Exception {
        long timeout = Math.min(in.timeoutMs().orElse(DEFAULT_TIMEOUT_MS), MAX_TIMEOUT_MS);

        Process proc = new ProcessBuilder("bash", "-c", in.command())
                .directory(ctx.workingDir().toFile())
                .redirectErrorStream(true)               // ① merged fd
                .start();

        // ③ 취소되면 프로세스 종료. interrupt는 직속 프로세스만 SIGTERM(손자는 안 건드림),
        //    일반 취소·타임아웃은 손자까지 tree-kill.
        ctx.cancel().onCancel(() -> {
            if (ctx.cancel().isInterrupt()) proc.destroy();   // SIGTERM (직속 프로세스만)
            else treeKill(proc);
        });

        // 출력을 막히지 않게 별도 가상 스레드로 빨아들인다(11장 패턴).
        // StringBuffer(동기화 버퍼)인 이유: 드레인이 끝나기 전에 스냅숏을 읽는 경로가 있다(아래).
        StringBuffer buf = new StringBuffer();
        boolean[] truncated = new boolean[1];   // 출력이 상한을 넘어 잘렸는지
        Thread drain = Thread.ofVirtual().start(() -> drainCapped(proc.getInputStream(), buf, truncated));

        long started = System.nanoTime();
        boolean finished = proc.waitFor(timeout, TimeUnit.MILLISECONDS);
        boolean timedOut = false;
        if (!finished) {                                  // ② 타임아웃 → 강제 종료
            timedOut = true;
            treeKill(proc);
            proc.waitFor(2, TimeUnit.SECONDS);
        }
        boolean interrupted = ctx.cancel().isInterrupt();  // 사용자 interrupt만 '중단'(일반 abort와 구분)
        // 손자(nohup … &)가 파이프를 물려받으면 EOF가 안 온다 — join을 잔여 타임아웃으로 바운드
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        boolean drained = drain.join(Duration.ofMillis(Math.max(1_000, timeout - elapsedMs)));

        int code = proc.isAlive() ? -1 : proc.exitValue();
        String output = buf.toString();
        if (truncated[0])                                  // 실제로 잘렸을 때만 마커를 붙인다
            output += "\n… (출력이 잘렸습니다)";
        if (!drained)
            output += "\n… (백그라운드 프로세스가 출력 파이프를 잡고 있어 여기까지만 캡처)";

        return ToolResult.of(new Output(in.command(), output, code, interrupted, timedOut));
    }

    @Override
    public ToolResultBlock mapResult(Output out, String toolUseId) {
        String body = out.output();
        if (out.timedOut())    body += "\n[타임아웃으로 종료됨]";
        if (out.interrupted()) body += "\n[사용자가 중단함]";
        // 명령을 알므로 정확한 해석(grep 무매치 등은 오류 아님). 타임아웃·중단은 오류로 보지 않는다.
        boolean isError = !out.interrupted() && !out.timedOut()
                && isError(out.command(), out.exitCode());
        if (out.exitCode() != 0 && !out.interrupted())
            body += "\n[exit code: " + out.exitCode() + "]";
        return isError ? ToolResultBlock.error(toolUseId, body)
                       : ToolResultBlock.ok(toolUseId, body);
    }

    // ── 종료코드 해석 ────────────────────────────────────
    // commandSemantics: 0=성공, 1=정보(무매치/차이/거짓), 2+=진짜 오류.
    // 아래 명령들만 exit 1을 정상으로 본다(그 외에는 1도 오류). 2 이상은 항상 오류.
    private static final Set<String> EXIT1_OK =
            Set.of("grep", "egrep", "fgrep", "rg", "diff", "test", "[", "find", "cmp");

    /** 명령별 정확한 종료코드 해석. */
    public static boolean isError(String command, int exitCode) {
        if (exitCode == 0) return false;
        String prog = firstWord(command);
        if (exitCode == 1 && EXIT1_OK.contains(prog)) return false;   // 무매치/차이/거짓은 정상
        return true;                                                 // 그 외(특히 2+)는 오류
    }

    private static String firstWord(String cmd) {
        String s = cmd.strip();
        int sp = s.indexOf(' ');
        return sp < 0 ? s : s.substring(0, sp);
    }

    // ── 프로세스 종료 ────────────────────────────────────
    private static void treeKill(Process proc) {
        proc.descendants().forEach(ProcessHandle::destroyForcibly);   // ④ 손자까지
        proc.destroyForcibly();
    }

    private static void drainCapped(InputStream is, StringBuffer buf, boolean[] truncated) {
        try (var r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            char[] chunk = new char[8192];
            int n;
            while ((n = r.read(chunk)) != -1) {                 // 끝까지 읽어 파이프 막힘 방지
                if (buf.length() >= MAX_OUTPUT) { truncated[0] = true; continue; }   // 이미 가득 → 이후는 버림
                int room = MAX_OUTPUT - buf.length();
                if (n > room) { buf.append(chunk, 0, room); truncated[0] = true; }   // 일부만 담고 잘림 표시
                else buf.append(chunk, 0, n);
            }
        } catch (IOException ignored) { /* 종료 시 정상 */ }
    }
}
