package agent.tool.process;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class RipGrep {
    private RipGrep() {}

    public record Result(int exitCode, List<String> lines) {
        public boolean matched() { return exitCode == 0; }
        public boolean noMatch() { return exitCode == 1; }
    }

    public static Result run(Path workingDir, Duration timeout, List<String> args)
            throws IOException, InterruptedException {

        List<String> cmd = new ArrayList<>();
        cmd.add("rg");
        cmd.addAll(args);

        Process proc = new ProcessBuilder(cmd)
                .directory(workingDir.toFile())
                .redirectErrorStream(false)
                .start();

        // ③ stdout·stderr를 둘 다 별도 가상 스레드로 빨아들인다.
        //    한쪽만 읽으면 다른 쪽 파이프(보통 64KB)가 차서 자식이 멈춘다(데드락).
        List<String> out = new ArrayList<>();
        StringBuilder err = new StringBuilder();
        Thread drainOut = drain(proc.getInputStream(), out::add);
        Thread drainErr = drain(proc.getErrorStream(), line -> err.append(line).append('\n'));

        // ② 타임아웃 → 강제 종료
        if (!proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            proc.destroyForcibly();
            throw new IOException("ripgrep 타임아웃(" + timeout.toSeconds() + "s)");
        }
        drainOut.join();
        drainErr.join();

        int code = proc.exitValue();
        if (code >= 2) {                                       // ① 2+ 만 진짜 오류
            throw new IOException("ripgrep 오류(exit " + code + "): " + err.toString().strip());
        }
        return new Result(code, out);                          // 0(매치)·1(무매치)은 정상
    }

    /** 자식 프로세스의 한 스트림을 줄 단위로 빨아들이는 가상 스레드. */
    private static Thread drain(InputStream stream, java.util.function.Consumer<String> sink) {
        return Thread.ofVirtual().start(() -> {
            try (var br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sink.accept(line);
            } catch (IOException ignored) { /* 프로세스 종료 시 정상 */ }
        });
    }
}
