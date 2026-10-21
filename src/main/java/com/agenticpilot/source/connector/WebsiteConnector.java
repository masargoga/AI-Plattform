package com.agenticpilot.source.connector;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import com.agenticpilot.source.SourceKind;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Reads a website: one host only, public addresses only (see {@link SafeFetcher}), robots.txt respected, a limited number of
 * pages, a pause between requests and time and size limits. Only http and https are accepted.
 */
public class WebsiteConnector implements SourceConnector {

    private static final Pattern SKIPPED_EXTENSIONS = Pattern.compile(
            "(?i)\\.(png|jpe?g|gif|svg|webp|ico|pdf|zip|gz|tgz|mp[34]|avi|mov|css|js|json|xml|woff2?|ttf|exe|dmg|docx?|xlsx?|pptx?)$");
    private static final String TOKEN = "agenticpilotbot";
    private static final int MAX_TEXT = 200_000;

    private final WebsiteProperties props;
    private final SafeFetcher fetcher;

    public WebsiteConnector(WebsiteProperties props, SafeFetcher fetcher) {
        this.props = props;
        this.fetcher = fetcher;
    }

    @Override
    public SourceKind kind() {
        return SourceKind.WEBSITE;
    }

    @Override
    public void validate(String location) {
        parse(location, props.allowPrivateAddresses());
    }

    /** The URL if acceptable: http or https, a real host name (no IP address), no credentials, a normal port. */
    public static URI parse(String location, boolean testMode) {
        URI uri;
        try {
            uri = new URI(location == null ? "" : location.strip());
        } catch (URISyntaxException e) {
            throw new FetchException("error.url.invalid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new FetchException("error.url.scheme");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new FetchException("error.url.invalid");
        }
        if (uri.getUserInfo() != null) {
            throw new FetchException("error.url.credentials");
        }
        if (!testMode) {
            if (host.contains(":") || host.matches("[0-9.]+")) {
                throw new FetchException("error.url.ipLiteral");
            }
            int port = uri.getPort();
            if (port != -1 && port != 80 && port != 443) {
                throw new FetchException("error.url.port");
            }
        }
        return uri.normalize();
    }

    @Override
    public SyncResult sync(String location, Predicate<String> hostAllowed) {
        URI start = parse(location, props.allowPrivateAddresses());
        String host = start.getHost().toLowerCase(Locale.ROOT);
        if (!hostAllowed.test(host)) {
            throw new FetchException("This domain is not on the allow list");
        }
        RobotsRules robots = loadRobots(start);
        long delayMillis = Math.max(props.requestDelay().toMillis(), Math.min(robots.crawlDelay().toMillis(), 10_000));

        List<Page> pages = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ArrayDeque<URI> queue = new ArrayDeque<>();
        queue.add(clean(start));
        seen.add(clean(start).toString());
        int skipped = 0;
        int failed = 0;
        boolean first = true;
        Set<String> hashes = new HashSet<>();
        while (!queue.isEmpty() && pages.size() < props.maxPages()) {
            URI url = queue.poll();
            if (!robots.isAllowed(pathAndQuery(url))) {
                skipped++;
                continue;
            }
            if (!first) {
                pause(delayMillis);
            }
            first = false;
            try {
                Fetched f = fetchFollowingRedirects(url, host, robots);
                if (f == null) {
                    skipped++;
                    continue;
                }
                Document doc = Jsoup.parse(new String(f.body(), java.nio.charset.StandardCharsets.UTF_8), f.url().toString());
                String text = text(doc);
                if (!text.isBlank() && hashes.add(Integer.toHexString(text.hashCode()) + text.length())) {
                    pages.add(new Page(f.url().toString(), title(doc, f.url()), text));
                }
                for (Element a : doc.select("a[href]")) {
                    URI link = link(a.attr("abs:href"), host);
                    if (link != null && seen.add(link.toString()) && seen.size() <= props.maxPages() * 20) {
                        queue.add(link);
                    }
                }
            } catch (FetchException e) {
                if (url.equals(clean(start)) && pages.isEmpty()) {
                    throw e; // the start page itself failed: the whole sync fails with a clear reason
                }
                failed++;
            }
        }
        if (pages.isEmpty()) {
            throw new FetchException(skipped > 0 ? "The robots.txt of the site does not allow reading any page" : "No readable pages were found");
        }
        return new SyncResult(pages, skipped, failed);
    }

    private record Fetched(URI url, byte[] body) {
    }

    /** Fetches a page; follows redirects only inside the same host. Null when the page is skipped (robots, type, redirect off-site). */
    private Fetched fetchFollowingRedirects(URI start, String host, RobotsRules robots) {
        URI current = start;
        for (int hop = 0; hop <= props.maxRedirects(); hop++) {
            SafeFetcher.Response r = fetcher.fetch(current);
            if (r.status() / 100 == 3 && r.location() != null) {
                URI next = clean(r.location());
                String scheme = next.getScheme() == null ? "" : next.getScheme().toLowerCase(Locale.ROOT);
                if (!(scheme.equals("http") || scheme.equals("https")) || next.getHost() == null || !next.getHost().equalsIgnoreCase(host)) {
                    return null;
                }
                if (!robots.isAllowed(pathAndQuery(next))) {
                    return null;
                }
                current = next;
                continue;
            }
            if (r.status() / 100 != 2) {
                throw new FetchException("A page answered with status " + r.status());
            }
            String type = r.contentType().toLowerCase(Locale.ROOT);
            if (!type.startsWith("text/html") && !type.startsWith("text/plain") && !type.startsWith("application/xhtml")) {
                return null;
            }
            return new Fetched(current, r.body());
        }
        throw new FetchException("Too many redirects");
    }

    private RobotsRules loadRobots(URI start) {
        URI robotsUri = URI.create(start.getScheme() + "://" + start.getRawAuthority() + "/robots.txt");
        SafeFetcher.Response r;
        try {
            r = fetcher.fetch(robotsUri);
            if (r.status() / 100 == 3 && r.location() != null) {
                URI next = r.location();
                if (next.getHost() != null && next.getHost().equalsIgnoreCase(start.getHost())) {
                    r = fetcher.fetch(next);
                }
            }
        } catch (FetchException e) {
            throw new FetchException("robots.txt could not be read: " + e.getMessage(), e);
        }
        if (r.status() / 100 == 2) {
            return RobotsRules.parse(new String(r.body(), java.nio.charset.StandardCharsets.UTF_8), TOKEN);
        }
        if (r.status() / 100 == 4) {
            return RobotsRules.allowAll();
        }
        return RobotsRules.disallowAll();
    }

    /** Lower-case scheme, no fragment, "/" for an empty path. */
    private static URI clean(URI uri) {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        try {
            return new URI(uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority() + path + query);
        } catch (URISyntaxException e) {
            return uri;
        }
    }

    /** An absolute same-host http(s) link to a page that is worth reading, or null. */
    private static URI link(String href, String host) {
        if (href.isBlank()) {
            return null;
        }
        try {
            URI u = new URI(href);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || u.getHost() == null || !u.getHost().equalsIgnoreCase(host)) {
                return null;
            }
            if (u.getRawPath() != null && SKIPPED_EXTENSIONS.matcher(u.getRawPath()).find()) {
                return null;
            }
            return clean(u);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static String pathAndQuery(URI u) {
        String p = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
        return u.getRawQuery() == null ? p : p + "?" + u.getRawQuery();
    }

    private static String title(Document doc, URI url) {
        String t = doc.title().strip();
        if (t.isEmpty()) {
            Element h1 = doc.selectFirst("h1");
            t = h1 == null ? "" : h1.text().strip();
        }
        return t.isEmpty() ? url.getHost() + pathAndQuery(url) : t;
    }

    private static String text(Document doc) {
        Element body = doc.body();
        if (body == null) {
            return "";
        }
        body.select("script,style,noscript,svg,iframe,nav,footer,form,template").remove();
        StringBuilder sb = new StringBuilder();
        String previous = "";
        for (Element e : body.select("h1,h2,h3,h4,h5,h6,p,li,td,th,pre,blockquote,dd,dt,figcaption")) {
            String t = e.text().strip();
            if (!t.isEmpty() && !t.equals(previous)) {
                sb.append(t).append("\n\n");
                previous = t;
            }
            if (sb.length() > MAX_TEXT) {
                break;
            }
        }
        String text = sb.toString().strip();
        return text.isEmpty() ? body.text().strip() : text;
    }

    private static void pause(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchException("The sync was interrupted");
        }
    }
}
