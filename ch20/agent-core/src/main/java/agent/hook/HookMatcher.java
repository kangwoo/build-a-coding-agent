package agent.hook;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public record HookMatcher(String matcher, List<HookCommand> hooks) {

    /** 단순 형태("Write|Edit") 판별용 — 이름·파이프 문자만이면 정규식으로 해석하지 않는다. */
    private static final Pattern SIMPLE = Pattern.compile("^[A-Za-z0-9_|]+$");

    /** matcher 문자열 → 컴파일된 정규식. record는 파생 필드를 못 가지므로 정적 캐시에 둔다 —
     *  키는 설정 파일에서 오는 소수의 matcher라 무한히 자라지 않는다. */
    private static final Map<String, Pattern> COMPILED = new ConcurrentHashMap<>();

    /** matcher가 도구 이름에 매치되나? 빈값/'*'=전체, 영문/파이프=정확매치, 그 외=정규식. */
    public boolean matches(String toolName) {
        if (matcher == null || matcher.isBlank() || matcher.equals("*")) return true;
        if (SIMPLE.matcher(matcher).matches()) {                         // "Write|Edit" 형태
            for (String m : matcher.split("\\|")) if (m.equals(toolName)) return true;
            return false;
        }
        return COMPILED.computeIfAbsent(matcher, Pattern::compile)
                .matcher(toolName).find();                               // 정규식
    }
}
