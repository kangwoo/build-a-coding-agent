package agent.command;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Arguments {
    private Arguments() {}

    // $ARGUMENTS와 $1, $2 … (숫자 경계까지) — $10이 $1로 부분 치환되는 함정을 피한다.
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$ARGUMENTS|\\$(\\d+)");

    /** $ARGUMENTS, $1, $2 … 치환. placeholder가 없으면 끝에 'ARGUMENTS: …' 추가. */
    public static String substitute(String template, String args) {
        String[] parts = args.isBlank() ? new String[0] : args.strip().split("\\s+");

        // 한 패스로 모든 placeholder를 치환 — 삽입된 인자 안의 '$1' 같은 텍스트가 2차 치환되지 않는다.
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        boolean hasPlaceholder = false;
        while (m.find()) {
            hasPlaceholder = true;
            String replacement;
            if (m.group(1) == null) {                     // $ARGUMENTS
                replacement = args;
            } else {                                      // $N — 인덱스가 범위를 벗어나면 원문 유지
                int idx = Integer.parseInt(m.group(1));   // 1-based
                replacement = (idx >= 1 && idx <= parts.length) ? parts[idx - 1] : m.group();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));   // 삽입값은 리터럴로
        }
        m.appendTail(sb);
        String out = sb.toString();

        if (!hasPlaceholder && !args.isBlank()) out = out + "\n\nARGUMENTS: " + args;
        return out;
    }
}
