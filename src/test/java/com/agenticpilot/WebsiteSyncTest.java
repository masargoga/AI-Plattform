package com.agenticpilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentService;
import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.runtime.Retriever;
import com.agenticpilot.source.AllowedDomain;
import com.agenticpilot.source.AllowedDomainService;
import com.agenticpilot.source.DataSource;
import com.agenticpilot.source.DataSourceService;
import com.agenticpilot.source.SourceKind;
import com.agenticpilot.source.SourceSyncService;
import com.agenticpilot.source.SyncStatus;
import com.agenticpilot.support.AsUser;
import com.agenticpilot.support.Approve;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class WebsiteSyncTest {

    @Autowired AllowedDomainService allowedDomains;
    @Autowired DataSourceService sources;
    @Autowired SourceSyncService sync;
    @Autowired AgentService agents;
    @Autowired Retriever retriever;
    @Autowired JdbcTemplate jdbc;

    private HttpServer server;
    private final Map<String, String[]> routes = new ConcurrentHashMap<>();
    private String base;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String[] r = routes.getOrDefault(ex.getRequestURI().getRawPath(), new String[] {"404", "text/plain", "nope"});
            byte[] body = r[2].getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", r[1]);
            ex.sendResponseHeaders(Integer.parseInt(r[0]), body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        routes.put("/", new String[] {"200", "text/html", "<html><title>Help</title><body><p>Plan changes are prorated for unused days.</p><a href='/refunds'>r</a></body></html>"});
        routes.put("/refunds", new String[] {"200", "text/html", "<html><title>Refunds</title><body><p>Refunds take five days after approval.</p></body></html>"});
    }

    @AfterEach
    void cleanUp() {
        server.stop(0);
        AsUser.admin();
        allowedDomains.all().stream().filter(d -> d.getDomain().equals("127.0.0.1")).map(AllowedDomain::getId).forEach(allowedDomains::remove);
        AsUser.clear();
    }

    private void allowLocalhost() {
        AsUser.admin();
        if (allowedDomains.all().stream().noneMatch(d -> d.getDomain().equals("127.0.0.1"))) {
            allowedDomains.add("127.0.0.1");
        }
    }

    @Test
    void websiteSourcesNeedAnAllowedDomainAndOnlyAdminsManageTheList() {
        AsUser.owner();
        assertThatThrownBy(() -> sources.create("Blocked " + System.nanoTime(), SourceKind.WEBSITE, base)).isInstanceOf(InvalidInputException.class)
                .hasMessage("error.domain.notAllowed");
        assertThatThrownBy(() -> allowedDomains.add("example.org")).isInstanceOf(AccessDeniedException.class);
        assertThat(allowedDomains.all()).isNotNull(); // everyone can read the list

        allowLocalhost();
        assertThat(sources.create("Allowed " + System.nanoTime(), SourceKind.WEBSITE, base).getKind()).isEqualTo(SourceKind.WEBSITE);
        assertThatThrownBy(() -> allowedDomains.add("127.0.0.1")).hasMessage("error.domain.exists");
        for (String bad : new String[] {"", "localhost", "exa mple.com", "-bad.com", "a..b.com", "http://"}) {
            assertThatThrownBy(() -> allowedDomains.add(bad)).as(bad).isInstanceOf(InvalidInputException.class);
        }
    }

    @Test
    void domainMatchingCoversSubdomainsButNotLookAlikes() {
        List<String> allowed = List.of("example.com");
        assertThat(AllowedDomainService.matches(allowed, "example.com")).isTrue();
        assertThat(AllowedDomainService.matches(allowed, "help.example.com")).isTrue();
        assertThat(AllowedDomainService.matches(allowed, "HELP.Example.COM")).isTrue();
        assertThat(AllowedDomainService.matches(allowed, "badexample.com")).isFalse();
        assertThat(AllowedDomainService.matches(allowed, "example.com.evil.org")).isFalse();
        assertThat(AllowedDomainService.matches(List.of(), "example.com")).isFalse();
    }

    @Test
    void syncStoresPagesMakesThemSearchableAndKeepsLastGoodContentOnFailure() {
        allowLocalhost();
        AsUser.owner();
        DataSource site = sources.create("Site " + System.nanoTime(), SourceKind.WEBSITE, base);
        Agent agent = agents.create("Web " + System.nanoTime(), "j", List.of(), List.of(site.getId()));
        Approve.agent(agents, agent.getId());

        DataSource synced = sync.runSync(site.getId(), "owner");
        assertThat(synced.getStatus()).isEqualTo(SyncStatus.SYNCED);
        assertThat(synced.getItemCount()).isEqualTo(2);
        assertThat(synced.getLastSynced()).isNotNull();
        var hits = retriever.retrieve(agent.getId(), "How long do refunds take?", 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).documentTitle()).isEqualTo("Refunds");
        assertThat(hits.get(0).text()).contains("five days");
        assertThat(jdbc.queryForObject("select count(*) from source_document where source_id = ? and uri like 'http%'", Integer.class, site.getId())).isEqualTo(2);

        // the site breaks: status Error with a reason, the earlier content stays searchable
        routes.clear();
        DataSource failed = sync.runSync(site.getId(), "owner");
        assertThat(failed.getStatus()).isEqualTo(SyncStatus.ERROR);
        assertThat(failed.getLastError()).contains("404");
        assertThat(failed.getItemCount()).isEqualTo(2);
        assertThat(retriever.retrieve(agent.getId(), "refunds", 3)).isNotEmpty();
        assertThat(jdbc.queryForObject("select count(*) from audit_entry where event like 'Website source%' and actor = 'owner'", Integer.class)).isGreaterThanOrEqualTo(2);

        // a changed site replaces the pages
        routes.put("/", new String[] {"200", "text/html", "<html><title>New</title><body><p>Completely different content about warranties.</p></body></html>"});
        DataSource again = sync.runSync(site.getId(), "owner");
        assertThat(again.getStatus()).isEqualTo(SyncStatus.SYNCED);
        assertThat(again.getLastError()).isNull();
        assertThat(again.getItemCount()).isEqualTo(1);
        assertThat(retriever.retrieve(agent.getId(), "refunds", 3)).isEmpty();
        assertThat(retriever.retrieve(agent.getId(), "warranties", 3)).hasSize(1);
    }

    @Test
    void removingTheDomainStopsFurtherSyncs() {
        allowLocalhost();
        AsUser.owner();
        DataSource site = sources.create("Site " + System.nanoTime(), SourceKind.WEBSITE, base);
        AsUser.admin();
        allowedDomains.all().stream().filter(d -> d.getDomain().equals("127.0.0.1")).map(AllowedDomain::getId).forEach(allowedDomains::remove);
        AsUser.owner();
        assertThatThrownBy(() -> sync.start(site.getId(), null)).hasMessage("error.domain.notAllowed");
        DataSource result = sync.runSync(site.getId(), "owner");
        assertThat(result.getStatus()).isEqualTo(SyncStatus.ERROR);
        assertThat(result.getLastError()).contains("allow list");
    }

    @Test
    void backgroundSyncRunsOnceAtATime() throws Exception {
        allowLocalhost();
        AsUser.owner();
        DataSource site = sources.create("Site " + System.nanoTime(), SourceKind.WEBSITE, base);
        CountDownLatch done = new CountDownLatch(1);
        DataSource started = sync.start(site.getId(), d -> done.countDown());
        assertThat(started.getStatus()).isEqualTo(SyncStatus.SYNCING);
        assertThatThrownBy(() -> sync.start(site.getId(), null)).hasMessage("error.sync.running");
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(sources.get(site.getId()).getStatus()).isEqualTo(SyncStatus.SYNCED);
    }
}
