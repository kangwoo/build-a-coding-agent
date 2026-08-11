package agent.hook;

import agent.message.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class CommandHookExecutor {

    public HookResult execute(HookCommand.Command hook, JsonNode input, Path cwd) {
        try {
            Process proc = new ProcessBuilder("bash", "-c", hook.command())
                    .directory(cwd.toFile())
                    .redirectErrorStream(false)
                    .start();

            // ① stdin에 JSON + 개행(필수)
            try (var os = proc.getOutputStream()) {
                os.write((Json.write(input) + "\n").getBytes(StandardCharsets.UTF_8));
            }

            // ② stdout·stderr를 각각 가상 스레드로 빨아들인다(11장 ripgrep 패턴).
            //    waitFor 전에 EOF까지 읽으면 타임아웃이 무력화되고, 한쪽만 읽으면 파이프가 차 데드락.
            StringBuffer outBuf = new StringBuffer(), errBuf = new StringBuffer();
            Thread outDrain = drain(proc.getInputStream(), outBuf);
            Thread errDrain = drain(proc.getErrorStream(), errBuf);

            long timeout = hook.timeout() != null ? hook.timeout() : 60;
            if (!proc.waitFor(timeout, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return new HookResult(HookResult.Outcome.NON_BLOCKING_ERROR,
                        Optional.empty(), Optional.of("훅 타임아웃"), Optional.empty());
            }
            outDrain.join(1_000);                        // 드레인 마무리 대기(바운드)
            errDrain.join(1_000);
            int code = proc.exitValue();
            String stdout = outBuf.toString(), stderr = errBuf.toString();

            // ③ JSON 출력이면 파싱, 아니면 종료코드 기반(exit 2의 피드백은 stderr 우선)
            if (stdout.strip().startsWith("{")) return parseJson(stdout, code);
            return fromExitCode(code, stderr.isBlank() ? stdout : stderr);
        } catch (Exception e) {
            return new HookResult(HookResult.Outcome.NON_BLOCKING_ERROR,
                    Optional.empty(), Optional.of(e.getMessage()), Optional.empty());
        }
    }

    private HookResult fromExitCode(int code, String out) {
        return switch (code) {
            case 0 -> HookResult.success();
            case 2 -> new HookResult(HookResult.Outcome.BLOCKING,                 // 차단!
                    Optional.of("deny"), Optional.of(out), Optional.empty());
            default -> new HookResult(HookResult.Outcome.NON_BLOCKING_ERROR,
                    Optional.empty(), Optional.of(out), Optional.empty());
        };
    }

    private HookResult parseJson(String stdout, int code) {
        JsonNode j = Json.read(stdout, JsonNode.class);
        return new HookResult(
                code == 2 ? HookResult.Outcome.BLOCKING : HookResult.Outcome.SUCCESS,
                Optional.ofNullable(j.path("permissionDecision").asText(null)),
                Optional.ofNullable(j.path("message").asText(null)),
                Optional.ofNullable(j.path("additionalContext").asText(null)));
    }

    /** 한 스트림을 EOF까지 읽는 가상 스레드(11장 ripgrep 패턴). */
    private static Thread drain(InputStream is, StringBuffer sb) {
        return Thread.ofVirtual().start(() -> {
            try (var r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            } catch (IOException ignored) { /* 프로세스 종료 시 정상 */ }
        });
    }
}
