package agent.tool.builtin;

import java.util.List;

/** 웹 검색 백엔드. 키·API가 제각각이므로 구현을 주입한다. */
public interface SearchBackend {
    record Hit(String title, String url, String snippet) {}
    List<Hit> search(String query, int limit) throws Exception;
}
