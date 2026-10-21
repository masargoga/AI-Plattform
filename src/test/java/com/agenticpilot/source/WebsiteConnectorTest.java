package com.agenticpilot.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticpilot.source.connector.AddressPolicy;
import com.agenticpilot.source.connector.FetchException;
import com.agenticpilot.source.connector.RobotsRules;
import com.agenticpilot.source.connector.SafeFetcher;
import com.agenticpilot.source.connector.SourceConnector;
import com.agenticpilot.source.connector.WebsiteConnector;
import com.agenticpilot.source.connector.WebsiteProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class WebsiteConnectorTest {

    private HttpServer server;
    private SafeFetcher fetcher;
    private final List<String> requested = new CopyOnWriteArrayList<>();
    private final Map<String, String[]> routes = new ConcurrentHashMap<>(); // path -> {status, content-type, body or Location}

    @AfterEach
    void stop() throws Exception {
        if (server != null) {
            server.stop(0);
        }
        if (fetcher != null) {
            fetcher.close();
        }
    }

    private static WebsiteProperties props(int maxPages, Duration delay, boolean allowPrivate) {
        return new WebsiteProperties(maxPages, delay, Duration.ofSeconds(3), 20_000, 3, allowPrivate, "AgenticPilotBot/1.0");
    }

    private String start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getRawPath() + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
            requested.add(path);
            String[] r = routes.getOrDefault(path, new String[] {"404", "text/plain", "not found"});
            int status = Integer.parseInt(r[0]);
            byte[] body = (status / 100 == 3 ? "" : r[2]).getBytes(StandardCharsets.UTF_8);
            if (status / 100 == 3) {
                ex.getResponseHeaders().add("Location", r[2]);
            }
            ex.getResponseHeaders().add("Content-Type", r[1]);
            ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String[] html(String body) {
        return new String[] {"200", "text/html; charset=utf-8", "<html><head><title>T</title></head><body>" + body + "</body></html>"};
    }

    private WebsiteConnector connector(int maxPages, Duration delay) {
        fetcher = new SafeFetcher(props(maxPages, delay, true));
        return new WebsiteConnector(props(maxPages, delay, true), fetcher);
    }

    // ----- crawling -----

    @Test
    void crawlsOneHostFollowsLinksRespectsRobotsAndSkipsWhatItShould() throws Exception {
        String base = start();
        routes.put("/robots.txt", new String[] {"200", "text/plain", "User-agent: *\nDisallow: /private\nDisallow: /secret$\n"});
        routes.put("/", html("<h1>Home</h1><p>Welcome to the help center.</p><nav>MENU NAV</nav><script>var x=1;</script>"
                + "<a href='/a'>A</a><a href='/b#frag'>B</a><a href='/private/x'>P</a><a href='/secret'>S</a><a href='" + base + "/a'>dup</a>"
                + "<a href='http://example.org/off'>off</a><a href='mailto:a@b.c'>m</a><a href='/file.pdf'>pdf</a><a href='/notfound'>nf</a>"
                + "<a href='/redirect-same'>r1</a><a href='/redirect-off'>r2</a><a href='/image'>img</a><a href='/big'>big</a>"));
        routes.put("/a", html("<h1>Plans</h1><p>Plan changes are prorated.</p><li>One</li><li>Two</li>"));
        routes.put("/b", html("<p>Billing happens monthly.</p>"));
        routes.put("/private/x", html("<p>hidden</p>"));
        routes.put("/secret", html("<p>hidden too</p>"));
        routes.put("/redirect-same", new String[] {"302", "text/plain", "/a?via=redirect"});
        routes.put("/a?via=redirect", html("<p>Reached through a redirect.</p>"));
        routes.put("/redirect-off", new String[] {"302", "text/plain", "http://example.org/elsewhere"});
        routes.put("/image", new String[] {"200", "image/png", "PNG"});
        routes.put("/big", html("<p>" + "x".repeat(30_000) + "</p>"));

        SourceConnector.SyncResult result = connector(50, Duration.ZERO).sync(base + "/", h -> true);

        List<String> uris = result.pages().stream().map(SourceConnector.Page::uri).toList();
        assertThat(uris).contains(base + "/", base + "/a", base + "/b", base + "/a?via=redirect");
        assertThat(uris).noneMatch(u -> u.contains("private") || u.contains("secret") || u.contains("example.org") || u.contains("file.pdf"));
        assertThat(requested).doesNotContain("/private/x", "/secret", "/file.pdf");
        assertThat(result.skippedByRobots()).isGreaterThanOrEqualTo(2);
        assertThat(result.failedPages()).isGreaterThanOrEqualTo(2); // /notfound and /big
        SourceConnector.Page home = result.pages().get(0);
        assertThat(home.title()).isEqualTo("T");
        assertThat(home.text()).contains("Welcome to the help center.").doesNotContain("MENU NAV").doesNotContain("var x");
        assertThat(result.pages().stream().filter(p -> p.uri().endsWith("/a")).findFirst().orElseThrow().text()).contains("Plan changes are prorated.", "One", "Two");
        assertThat(requested.stream().filter(p -> p.equals("/a")).count()).isEqualTo(1); // duplicates are fetched once
        assertThat(requested).first().isEqualTo("/robots.txt");
    }

    @Test
    void pageLimitStopsTheCrawl() throws Exception {
        String base = start();
        StringBuilder links = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            links.append("<a href='/p").append(i).append("'>x</a>");
            routes.put("/p" + i, html("<p>Page " + i + "</p>"));
        }
        routes.put("/", html("<p>Index</p>" + links));
        var result = connector(5, Duration.ZERO).sync(base + "/", h -> true);
        assertThat(result.pages()).hasSize(5);
        assertThat(requested.stream().filter(p -> p.startsWith("/p")).count()).isLessThanOrEqualTo(4);
    }

    @Test
    void requestsArePacedByTheConfiguredDelay() throws Exception {
        String base = start();
        routes.put("/", html("<p>Index</p><a href='/a'>a</a><a href='/b'>b</a>"));
        routes.put("/a", html("<p>A</p>"));
        routes.put("/b", html("<p>B</p>"));
        long t0 = System.nanoTime();
        connector(10, Duration.ofMillis(200)).sync(base + "/", h -> true);
        assertThat((System.nanoTime() - t0) / 1_000_000).isGreaterThanOrEqualTo(380); // 3 pages: two pauses of 200 ms
    }

    @Test
    void robotsProblemsAreHandledSafely() throws Exception {
        String base = start();
        routes.put("/", html("<p>Index</p>"));
        assertThat(connector(5, Duration.ZERO).sync(base + "/", h -> true).pages()).hasSize(1);          // 404: allowed

        requested.clear();
        routes.put("/robots.txt", new String[] {"500", "text/plain", "oops"});
        assertThatThrownBy(() -> connector(5, Duration.ZERO).sync(base + "/", h -> true)).hasMessageContaining("robots.txt"); // 5xx: nothing allowed
        assertThat(requested).doesNotContain("/").as("the page itself is not requested when robots.txt cannot be read");
    }

    @Test
    void failuresOfTheStartPageAndTheAllowListFailTheSync() throws Exception {
        String base = start();
        assertThatThrownBy(() -> connector(5, Duration.ZERO).sync(base + "/", h -> true)).isInstanceOf(FetchException.class).hasMessageContaining("status 404");
        routes.put("/", html("<p>x</p>"));
        requested.clear();
        assertThatThrownBy(() -> connector(5, Duration.ZERO).sync(base + "/", h -> false)).isInstanceOf(FetchException.class).hasMessageContaining("allow list");
        assertThat(requested).as("nothing is requested for a host that is not allowed").isEmpty();
    }

    // ----- SSRF -----

    @ParameterizedTest
    @CsvSource({"127.0.0.1,false", "127.8.8.8,false", "10.0.0.5,false", "172.16.0.1,false", "172.31.255.255,false", "192.168.1.1,false", "169.254.169.254,false",
            "100.64.0.1,false", "0.0.0.0,false", "224.0.0.1,false", "255.255.255.255,false", "198.18.0.1,false", "192.0.0.8,false",
            "::1,false", "fe80::1,false", "fc00::1,false", "fd12:3456::1,false", "::ffff:127.0.0.1,false", "::ffff:10.1.2.3,false", "64:ff9b::a00:1,false",
            "2002:7f00:1::1,false", "2001:0:4136:e378:8000:63bf:3fff:fdd2,false", "::,false",
            "8.8.8.8,true", "1.1.1.1,true", "93.184.216.34,true", "172.32.0.1,true", "100.128.0.1,true", "2606:4700:4700::1111,true"})
    void onlyPublicAddressesAreAllowed(String address, boolean expected) throws Exception {
        assertThat(AddressPolicy.isPublic(InetAddress.getByName(address))).isEqualTo(expected);
    }

    @Test
    void theFetcherRefusesInternalAddressesAlsoByHostName() throws Exception {
        String base = start();
        routes.put("/", html("<p>secret internal page</p>"));
        try (SafeFetcher strict = new SafeFetcher(props(5, Duration.ZERO, false))) {
            int port = server.getAddress().getPort();
            assertThatThrownBy(() -> strict.fetch(java.net.URI.create("http://localhost:" + port + "/"))).isInstanceOf(FetchException.class).hasMessageContaining("not allowed");
            assertThatThrownBy(() -> strict.fetch(java.net.URI.create("http://127.0.0.1:" + port + "/"))).isInstanceOf(FetchException.class);
            assertThatThrownBy(() -> strict.fetch(java.net.URI.create("http://169.254.169.254/latest/meta-data/"))).isInstanceOf(FetchException.class);
        }
        assertThat(requested).isEmpty();
        assertThat(base).isNotBlank();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://example.com/", "file:///etc/passwd", "javascript:alert(1)", "http://127.0.0.1/", "http://[::1]/", "http://10.0.0.1/",
            "https://user:pw@example.com/", "http://example.com:22/", "http://example.com:8080/", "", "not a url", "http:///nohost"})
    void locationsAreCheckedBeforeAnythingIsFetched(String location) {
        assertThatThrownBy(() -> WebsiteConnector.parse(location, false)).isInstanceOf(FetchException.class).hasMessageStartingWith("error.url");
    }

    @Test
    void ordinaryLocationsAreAccepted() {
        assertThat(WebsiteConnector.parse("https://help.example.com/docs/", false).getHost()).isEqualTo("help.example.com");
        assertThat(WebsiteConnector.parse("http://example.com:80/x", false)).isNotNull();
        assertThat(WebsiteConnector.parse("https://example.com:443", false)).isNotNull();
    }

    // ----- robots.txt rules -----

    @Test
    void robotsRulesUseTheMostSpecificGroupAndTheLongestMatch() {
        String robots = """
                User-agent: *
                Disallow: /
                User-agent: AgenticPilotBot
                Disallow: /private
                Allow: /private/public
                Disallow: /*.json$
                Crawl-delay: 2
                """;
        RobotsRules rules = RobotsRules.parse(robots, "agenticpilotbot");
        assertThat(rules.isAllowed("/docs")).isTrue();
        assertThat(rules.isAllowed("/private/data")).isFalse();
        assertThat(rules.isAllowed("/private/public/x")).isTrue();
        assertThat(rules.isAllowed("/data.json")).isFalse();
        assertThat(rules.isAllowed("/data.json.txt")).isTrue();
        assertThat(rules.crawlDelay()).isEqualTo(Duration.ofSeconds(2));

        RobotsRules general = RobotsRules.parse("User-agent: *\nDisallow: /\n", "agenticpilotbot");
        assertThat(general.isAllowed("/anything")).isFalse();
        assertThat(RobotsRules.parse("User-agent: *\nDisallow:\n", "agenticpilotbot").isAllowed("/anything")).isTrue();
        assertThat(RobotsRules.parse("# nothing\n", "agenticpilotbot").isAllowed("/x")).isTrue();
        assertThat(RobotsRules.disallowAll().isAllowed("/")).isFalse();
        assertThat(RobotsRules.parse("User-agent: otherbot\nDisallow: /\n", "agenticpilotbot").isAllowed("/x")).isTrue();
        assertThat(RobotsRules.parse("User-agent: a\nUser-agent: *\nDisallow: /x # comment\n", "agenticpilotbot").isAllowed("/x/y")).isFalse();
    }
}
