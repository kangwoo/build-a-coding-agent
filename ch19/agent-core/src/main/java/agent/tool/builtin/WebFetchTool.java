package agent.tool.builtin;

import agent.message.ContentBlock.ToolResultBlock;
import agent.tool.*;
import agent.tool.schema.Desc;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.*;
import java.time.Duration;

public final class WebFetchTool implements Tool<WebFetchTool.Input, String> {

    private static final int MAX_CHARS = 50_000;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)        // 리다이렉트 추적(https→http 다운그레이드는 제외)
            .build();

    public record Input(@Desc("가져올 URL") String url) {}

    @Override public String name() { return "WebFetch"; }
    @Override public String description() { return "URL의 내용을 가져와 텍스트로 반환한다."; }
    @Override public Class<Input> inputType() { return Input.class; }
    @Override public boolean isReadOnly(Input in) { return true; }
    @Override public boolean isConcurrencySafe(Input in) { return true; }

    @Override
    public ValidationResult validateInput(Input in, ToolContext ctx) {
        URI uri;
        try { uri = URI.create(in.url()); }
        catch (RuntimeException e) { return ValidationResult.fail("잘못된 URL: " + in.url(), 1); }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https")))
            return ValidationResult.fail("http/https URL만 허용합니다: " + in.url(), 2);
        return ValidationResult.ok();
    }

    @Override
    public ToolResult<String> call(Input in, ToolContext ctx) throws Exception {
        URI uri = URI.create(in.url());
        guardPrivateTarget(uri);                                // SSRF 가드: 내부망이면 연결 전에 끊는다
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("user-agent", "coding-agent/0.1")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400)
            throw new IOException("HTTP " + resp.statusCode() + ": " + in.url());

        String text = stripHtml(resp.body());
        if (text.length() > MAX_CHARS) text = text.substring(0, MAX_CHARS) + "\n… (잘림)";
        return ToolResult.of(text);
    }

    /**
     * SSRF 가드: 호스트를 resolve해 루프백·사설(RFC 1918)·링크로컬(클라우드 메타데이터
     * 169.254.169.254 포함)·IPv6 ULA(fc00::/7) 대역이면 거부한다. isReadOnly=true라
     * 권한 게이트(16장)가 묻지 않고 통과시키므로, 이 검사가 내부망행을 막는 유일한 방어선이다.
     * 한계: 리다이렉트 홉은 재검사하지 않는다 — 같은 호스트 제한은 실서비스 몫(§11.7).
     */
    private static void guardPrivateTarget(URI uri) throws IOException {
        String host = uri.getHost();
        if (host == null) throw new IOException("호스트가 없는 URL: " + uri);
        InetAddress[] addrs;
        try { addrs = InetAddress.getAllByName(host); }
        catch (UnknownHostException e) { throw new IOException("호스트를 찾을 수 없습니다: " + host); }
        for (InetAddress a : addrs) {
            byte[] raw = a.getAddress();
            boolean ipv6Ula = raw.length == 16 && (raw[0] & 0xFE) == 0xFC;   // fc00::/7
            if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress()
                    || a.isSiteLocalAddress() || ipv6Ula)
                throw new IOException("사설/내부 주소는 차단합니다(SSRF): " + host + " → " + a.getHostAddress());
        }
    }

    /** 아주 단순한 태그 제거(실전에선 jsoup 등으로 본문 추출 권장). */
    private static String stripHtml(String html) {
        return html.replaceAll("(?s)<script.*?</script>", "")
                   .replaceAll("(?s)<style.*?</style>", "")
                   .replaceAll("<[^>]+>", " ")
                   .replaceAll("[ \\t]+", " ")
                   .replaceAll("(?m)^\\s+$", "")
                   .strip();
    }

    @Override
    public ToolResultBlock mapResult(String text, String toolUseId) {
        return ToolResultBlock.ok(toolUseId, text);
    }
}
