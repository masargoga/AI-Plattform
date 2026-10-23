package com.agenticpilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentService;
import com.agenticpilot.agent.AgentStatus;
import com.agenticpilot.api.ApiKeyService;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.guardrail.AgentGuardrailService;
import com.agenticpilot.guardrail.ApprovalAction;
import com.agenticpilot.guardrail.CustomRuleService;
import com.agenticpilot.guardrail.RuleAction;
import com.agenticpilot.guardrail.RuleScope;
import com.agenticpilot.runtime.PendingActionService;
import com.agenticpilot.source.DataSource;
import com.agenticpilot.source.DataSourceService;
import com.agenticpilot.source.SourceKind;
import com.agenticpilot.source.ingest.IngestionService;
import com.agenticpilot.support.ApiClient;
import com.agenticpilot.support.AsUser;
import com.agenticpilot.support.Approve;
import com.agenticpilot.support.FakeModelConfig;
import com.agenticpilot.support.FakeModelConfig.FakeModel;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(FakeModelConfig.class)
class ApiTest {

    @LocalServerPort int port;
    @Autowired AgentService agents;
    @Autowired AgentGuardrailService guardrails;
    @Autowired ApiKeyService keys;
    @Autowired CustomRuleService rules;
    @Autowired PendingActionService pending;
    @Autowired DataSourceService sources;
    @Autowired IngestionService ingestion;
    @Autowired AuditService audit;
    @Autowired FakeModel model;
    @Autowired JdbcTemplate jdbc;

    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
        model.reset();
    }

    @AfterEach
    void signOut() {
        AsUser.clear();
    }

    private record Setup(Agent agent, String token, UUID keyId) {
    }

    private Setup newAgentWithKey() {
        AsUser.owner();
        Agent agent = agents.create("Api " + System.nanoTime(), "Answers questions.", List.of(), List.of());
        Approve.agent(agents, agent.getId());
        var created = keys.create(agent.getId(), "test key");
        return new Setup(agent, created.token(), created.key().getId());
    }

    @Test
    void agentsThatAreNotApprovedYetAreNotUsableThroughTheApi() throws Exception {
        AsUser.owner();
        Agent draft = agents.create("Draft " + System.nanoTime(), "Not yet approved.", List.of(), List.of());
        String token = keys.create(draft.getId(), "early key").token();

        assertThat(api.get("/api/v1/agents", token).json().get("agents").size()).isZero();
        var message = api.post("/api/v1/agents/" + draft.getId() + "/messages", token, "{\"message\":\"hi\"}");
        assertThat(message.status()).isEqualTo(409);
        assertThat(message.json().get("code").asString()).isEqualTo("agent_not_approved");
        var check = api.post("/api/v1/guardrails/check", token, "{\"agentId\":\"" + draft.getId() + "\",\"text\":\"hi\"}");
        assertThat(check.status()).isEqualTo(409);
        assertThat(model.calls).isEmpty();

        Approve.agent(agents, draft.getId());
        assertThat(api.get("/api/v1/agents", token).json().get("agents").size()).isEqualTo(1);
        assertThat(api.post("/api/v1/agents/" + draft.getId() + "/messages", token, "{\"message\":\"hi\"}").status()).isEqualTo(200);

        AsUser.admin();
        assertThatThrownBy(() -> agents.retire(draft.getId())).hasMessage("error.agent.hasKeys"); // blocked while a key is active
    }

    // ----- authentication -----

    @Test
    void callsWithoutAValidKeyAreRefusedInTheErrorFormat() throws Exception {
        var none = api.get("/api/v1/agents", null);
        assertThat(none.status()).isEqualTo(401);
        assertThat(none.json().get("code").asString()).isEqualTo("unauthorized");
        assertThat(none.json().get("requestId").asString()).isNotBlank();
        assertThat(none.header("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(none.header("X-Request-Id")).isEqualTo(none.json().get("requestId").asString());

        for (String bad : new String[] {"garbage", "ap_", "ap_abcdefgh_wrongsecret", "ap_" + "x".repeat(300)}) {
            assertThat(api.get("/api/v1/agents", bad).status()).as(bad.substring(0, Math.min(12, bad.length()))).isEqualTo(401);
        }
        assertThat(api.send("GET", "/api/v1/agents", null, null, null).status()).isEqualTo(401);
    }

    @Test
    void keyIsShownOnceStoredHashedAndCanBeRevoked() throws Exception {
        Setup s = newAgentWithKey();
        String secret = s.token().substring(s.token().lastIndexOf('_') + 1);
        assertThat(s.token()).startsWith("ap_");
        var row = jdbc.queryForMap("select prefix, secret_hash from api_key where id = ?", s.keyId());
        assertThat(row.get("secret_hash").toString()).hasSize(64).doesNotContain(secret);
        assertThat(s.token()).contains(row.get("prefix").toString());
        assertThat(keys.list(s.agent().getId())).hasSize(1);

        assertThat(api.get("/api/v1/agents", s.token()).status()).isEqualTo(200);
        keys.revoke(s.keyId());
        var after = api.get("/api/v1/agents", s.token());
        assertThat(after.status()).isEqualTo(401);
        assertThat(keys.list(s.agent().getId()).get(0).isRevoked()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from audit_entry where event like 'API key%' and agent_id = ?", Integer.class, s.agent().getId())).isEqualTo(2);
    }

    @Test
    void onlyOwnerOrAdminManageKeys() {
        Setup s = newAgentWithKey();
        AsUser.admin();
        assertThat(keys.list(s.agent().getId())).hasSize(1);
        Agent adminAgent = agents.create("AdminAgent " + System.nanoTime(), "j", List.of(), List.of());
        Approve.agent(agents, adminAgent.getId());
        AsUser.owner();
        assertThatThrownBy(() -> keys.create(adminAgent.getId(), "x")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> keys.list(adminAgent.getId())).isInstanceOf(AccessDeniedException.class);
    }

    // ----- agents and scoping -----

    @Test
    void listsOnlyTheKeysOwnAgentAndOtherAgentsAreOffLimits() throws Exception {
        Setup a = newAgentWithKey();
        Setup b = newAgentWithKey();
        var list = api.get("/api/v1/agents", a.token());
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.json().get("agents")).hasSize(1);
        assertThat(list.json().get("agents").get(0).get("id").asString()).isEqualTo(a.agent().getId().toString());
        assertThat(list.json().get("agents").get(0).get("kind").asString()).isEqualTo("managed");
        assertThat(list.header("X-RateLimit-Limit")).isNotNull();

        assertThat(api.post("/api/v1/agents/" + b.agent().getId() + "/messages", a.token(), "{\"message\":\"hi\"}").status()).isEqualTo(403);
        assertThat(api.post("/api/v1/guardrails/check", a.token(), "{\"agentId\":\"" + b.agent().getId() + "\",\"text\":\"hi\"}").status()).isEqualTo(403);
        assertThat(model.calls).isEmpty();
    }

    // ----- messages -----

    @Test
    void messageEndpointAnswersFromDocumentsAndReportsEverything() throws Exception {
        AsUser.owner();
        DataSource docs = sources.create("ApiDocs " + System.nanoTime(), SourceKind.UPLOAD, "files");
        ingestion.addFile(docs.getId(), "faq.txt", "Plans are prorated per day of the billing cycle.".getBytes(StandardCharsets.UTF_8));
        Agent agent = agents.create("Api " + System.nanoTime(), "Answers billing questions.", List.of(), List.of(docs.getId()));
        Approve.agent(agents, agent.getId());
        String token = keys.create(agent.getId(), "k").token();
        model.answer = r -> "Yes, prorated.";

        var r = api.post("/api/v1/agents/" + agent.getId() + "/messages", token, "{\"message\":\"Are plans prorated? My mail is anna@mail.example\"}");
        assertThat(r.status()).isEqualTo(200);
        JsonNode j = r.json();
        assertThat(j.get("status").asString()).isEqualTo("answered");
        assertThat(j.get("answer").asString()).isEqualTo("Yes, prorated.");
        assertThat(j.get("sourcesUsed").get(0).asString()).endsWith("/ faq.txt");
        assertThat(j.get("decisions").get(0).get("category").asString()).isEqualTo("PERSONAL_DATA");
        assertThat(j.get("decisions").get(0).get("outcome").asString()).isEqualTo("masked");
        assertThat(j.get("requestId").asString()).isEqualTo(r.header("X-Request-Id"));
        assertThat(j.get("conversationId").asString()).isNotBlank();
        assertThat(model.last().messages().get(0).text()).contains("[email]").doesNotContain("anna");
        assertThat(r.body()).doesNotContain("anna@");

        // conversation continues, and belongs to this key
        var second = api.post("/api/v1/agents/" + agent.getId() + "/messages", token,
                "{\"message\":\"And mid-cycle?\",\"conversationId\":\"" + j.get("conversationId").asString() + "\"}");
        assertThat(second.status()).isEqualTo(200);
        assertThat(model.last().messages()).hasSize(3);
        String otherToken = keys.create(agent.getId(), "other").token();
        var stolen = api.post("/api/v1/agents/" + agent.getId() + "/messages", otherToken,
                "{\"message\":\"hi\",\"conversationId\":\"" + j.get("conversationId").asString() + "\"}");
        assertThat(stolen.status()).isEqualTo(403);

        // audited as the key
        String prefix = token.substring(3, 11);
        assertThat(jdbc.queryForObject("select count(*) from audit_entry where actor = ? and event like 'Answered%'", Integer.class, "api:" + prefix)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("select channel from conversation where id = ?", String.class, UUID.fromString(j.get("conversationId").asString()))).isEqualTo("API");
    }

    @Test
    void guardrailsStopMessagesWithoutCallingTheModelAndApprovalsCanBePolled() throws Exception {
        Setup s = newAgentWithKey();
        AsUser.owner();
        guardrails.setApproval(s.agent().getId(), ApprovalAction.REFUND, true);
        String path = "/api/v1/agents/" + s.agent().getId() + "/messages";

        var blocked = api.post(path, s.token(), "{\"message\":\"I have been sick for two weeks\"}");
        assertThat(blocked.status()).isEqualTo(200);
        assertThat(blocked.json().get("status").asString()).isEqualTo("blocked");
        assertThat(blocked.json().get("decisions").get(0).get("outcome").asString()).isEqualTo("blocked");

        var waiting = api.post(path, s.token(), "{\"message\":\"Please refund order 48213\"}");
        assertThat(waiting.status()).isEqualTo(202);
        assertThat(waiting.json().get("status").asString()).isEqualTo("needs_approval");
        String approvalUrl = waiting.json().get("approval").get("url").asString();
        assertThat(model.calls).isEmpty();

        var poll = api.get(approvalUrl, s.token());
        assertThat(poll.status()).isEqualTo(200);
        assertThat(poll.json().get("status").asString()).isEqualTo("pending");

        UUID approvalId = UUID.fromString(waiting.json().get("approval").get("id").asString());
        AsUser.admin();
        pending.approve(approvalId);
        assertThat(api.get(approvalUrl, s.token()).json().get("status").asString()).isEqualTo("approved");

        Setup other = newAgentWithKey();
        assertThat(api.get(approvalUrl, other.token()).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/approvals/" + UUID.randomUUID(), s.token()).status()).isEqualTo(404);
    }

    @Test
    void unavailableAgentsAnswerWithClearCodes() throws Exception {
        Setup s = newAgentWithKey();
        String path = "/api/v1/agents/" + s.agent().getId() + "/messages";

        AsUser.owner();
        agents.setStatus(s.agent().getId(), AgentStatus.PAUSED);
        var paused = api.post(path, s.token(), "{\"message\":\"hi\"}");
        assertThat(paused.status()).isEqualTo(409);
        assertThat(paused.json().get("code").asString()).isEqualTo("agent_paused");
        agents.setStatus(s.agent().getId(), AgentStatus.ACTIVE);

        model.configured = false;
        var noModel = api.post(path, s.token(), "{\"message\":\"hi\"}");
        assertThat(noModel.status()).isEqualTo(503);
        assertThat(noModel.json().get("code").asString()).isEqualTo("model_unavailable");
        model.configured = true;
        model.failing = true;
        assertThat(api.post(path, s.token(), "{\"message\":\"hi\"}").status()).isEqualTo(503);
        model.failing = false;

        jdbc.update("update agent set kind = 'EXTERNAL' where id = ?", s.agent().getId());
        var external = api.post(path, s.token(), "{\"message\":\"hi\"}");
        assertThat(external.status()).isEqualTo(409);
        assertThat(external.json().get("code").asString()).isEqualTo("agent_not_managed");
        assertThat(api.post("/api/v1/guardrails/check", s.token(), "{\"agentId\":\"" + s.agent().getId() + "\",\"text\":\"hi\"}").status()).isEqualTo(200);
    }

    @Test
    void badRequestsGetTheErrorFormat() throws Exception {
        Setup s = newAgentWithKey();
        String path = "/api/v1/agents/" + s.agent().getId() + "/messages";
        var empty = api.post(path, s.token(), "{\"message\":\"  \"}");
        assertThat(empty.status()).isEqualTo(400);
        assertThat(empty.json().get("code").asString()).isEqualTo("invalid_request");
        assertThat(api.post(path, s.token(), "{\"message\":").status()).isEqualTo(400);
        assertThat(api.post(path, s.token(), "{}").status()).isEqualTo(400);
        assertThat(api.post(path, s.token(), "{\"message\":\"" + "x".repeat(4001) + "\"}").json().get("code").asString()).isEqualTo("invalid_request");
        assertThat(api.send("POST", path, s.token(), "hello", "text/plain").status()).isEqualTo(415);
        assertThat(api.post("/api/v1/agents/not-a-uuid/messages", s.token(), "{\"message\":\"hi\"}").status()).isEqualTo(400);
        var unknown = api.get("/api/v1/nope", s.token());
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.json().get("code").asString()).isEqualTo("not_found");
        assertThat(api.send("DELETE", "/api/v1/agents", s.token(), null, null).status()).isEqualTo(405);
        assertThat(api.send("POST", path, s.token(), "x".repeat(300_000), "application/json").status()).isEqualTo(413);
        assertThat(api.get("/api/v1/approvals/xyz", s.token()).status()).isEqualTo(400);
    }

    // ----- guardrail check -----

    @Test
    void checkEndpointReturnsTheFourVerdicts() throws Exception {
        Setup s = newAgentWithKey();
        AsUser.owner();
        guardrails.setApproval(s.agent().getId(), ApprovalAction.REFUND, true);
        AsUser.admin();
        var rule = rules.propose(null, new CustomRuleService.RuleFields("Legal", "zxqlegal", RuleAction.BLOCK, "Ask a lawyer.", RuleScope.SELECTED,
                Set.of(s.agent().getId()), ""), "n");
        AsUser.admin2();
        rules.approve(rule.getId());
        AsUser.clear();

        String id = s.agent().getId().toString();
        var allow = api.post("/api/v1/guardrails/check", s.token(), "{\"agentId\":\"" + id + "\",\"text\":\"Where is my order?\"}").json();
        assertThat(allow.get("decision").asString()).isEqualTo("allow");
        assertThat(allow.get("text").asString()).isEqualTo("Where is my order?");

        var mask = api.post("/api/v1/guardrails/check", s.token(), "{\"agentId\":\"" + id + "\",\"text\":\"Mail anna@mail.example please\"}").json();
        assertThat(mask.get("decision").asString()).isEqualTo("mask");
        assertThat(mask.get("text").asString()).isEqualTo("Mail [email] please");

        var block = api.post("/api/v1/guardrails/check", s.token(), "{\"agentId\":\"" + id + "\",\"text\":\"Can I zxqlegal you?\"}").json();
        assertThat(block.get("decision").asString()).isEqualTo("block");
        assertThat(block.get("message").asString()).isEqualTo("Ask a lawyer.");
        assertThat(block.get("text").asString()).isEmpty();
        assertThat(block.get("matchedRules").get(0).get("name").asString()).isEqualTo("Legal");
        assertThat(block.get("matchedRules").get(0).get("action").asString()).isEqualTo("blocked");

        var approval = api.post("/api/v1/guardrails/check", s.token(), "{\"agentId\":\"" + id + "\",\"text\":\"Refund order 5\"}").json();
        assertThat(approval.get("decision").asString()).isEqualTo("needs_approval");
        assertThat(api.get(approval.get("approval").get("url").asString(), s.token()).json().get("status").asString()).isEqualTo("pending");

        var output = api.post("/api/v1/guardrails/check", s.token(), "{\"agentId\":\"" + id + "\",\"text\":\"Reply to anna@mail.example\",\"direction\":\"output\"}").json();
        assertThat(output.get("decision").asString()).isEqualTo("mask");
        assertThat(output.get("text").asString()).isEqualTo("Reply to [email]");
        assertThat(api.post("/api/v1/guardrails/check", s.token(), "{\"agentId\":\"" + id + "\",\"text\":\"x\",\"direction\":\"sideways\"}").status()).isEqualTo(400);
        assertThat(api.post("/api/v1/guardrails/check", s.token(), "{\"text\":\"x\"}").status()).isEqualTo(400);
        assertThat(model.calls).isEmpty();
        String prefix = s.token().substring(3, 11);
        assertThat(jdbc.queryForObject("select count(*) from audit_entry where actor = ?", Integer.class, "api:" + prefix)).isGreaterThanOrEqualTo(5);
    }

    // ----- streaming -----

    private record Sse(String event, String data) {
    }

    private List<Sse> stream(String path, String token, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json").header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> r = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("content-type").orElse("")).startsWith("text/event-stream");
        List<Sse> events = new ArrayList<>();
        for (String block : r.body().split("\n\n")) {
            String name = null;
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring(6).strip();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).strip());
                }
            }
            if (name != null) {
                events.add(new Sse(name, data.toString()));
            }
        }
        return events;
    }

    @Test
    void streamingSendsDecisionsFirstThenCheckedTextThenDone() throws Exception {
        Setup s = newAgentWithKey();
        model.answer = r -> "Write to bob@mail.example today.\n- one\n- two\nSkill used: none";
        var events = stream("/api/v1/agents/" + s.agent().getId() + "/messages", s.token(), "{\"message\":\"Hello, mail me at anna@mail.example\",\"stream\":true}");
        assertThat(events.get(0).event()).isEqualTo("decision");
        assertThat(events.get(0).data()).contains("PERSONAL_DATA").contains("masked");
        List<Sse> deltas = events.stream().filter(e -> e.event().equals("delta")).toList();
        assertThat(deltas).isNotEmpty();
        String streamed = deltas.stream().map(e -> tools.jackson.databind.json.JsonMapper.builder().build().readTree(e.data()).get("text").asString()).reduce("", String::concat);
        assertThat(streamed).isEqualTo("Write to [email] today.\n- one\n- two").doesNotContain("bob");
        Sse done = events.get(events.size() - 1);
        assertThat(done.event()).isEqualTo("done");
        assertThat(done.data()).contains("\"status\":\"answered\"").contains("Write to [email] today.").doesNotContain("bob@");
        assertThat(events.indexOf(events.stream().filter(e -> e.event().equals("delta")).findFirst().orElseThrow()))
                .isGreaterThan(events.indexOf(events.get(0)));
    }

    @Test
    void streamingGuardrailStopsStillEndInDone() throws Exception {
        Setup s = newAgentWithKey();
        var events = stream("/api/v1/agents/" + s.agent().getId() + "/messages", s.token(), "{\"message\":\"I have been sick\",\"stream\":true}");
        assertThat(events).extracting(Sse::event).containsExactly("decision", "delta", "done");
        assertThat(events.get(2).data()).contains("\"status\":\"blocked\"");
        assertThat(model.calls).isEmpty();
    }

    // ----- OpenAPI -----

    @Test
    void openApiDescriptionIsPublicAndMatchesTheResponses() throws Exception {
        var doc = api.get("/v3/api-docs", null);
        assertThat(doc.status()).isEqualTo(200);
        JsonNode j = doc.json();
        assertThat(j.get("paths").propertyNames()).contains("/api/v1/agents", "/api/v1/agents/{agentId}/messages", "/api/v1/guardrails/check", "/api/v1/approvals/{approvalId}");
        assertThat(j.get("components").get("securitySchemes").get("bearerAuth").get("scheme").asString()).isEqualTo("bearer");
        assertThat(j.get("components").get("schemas").propertyNames()).contains("ApiError", "MessageResponse", "CheckResponse", "ApprovalResponse", "AgentList");

        // contract: the fields of real responses are exactly the properties the description promises
        Setup s = newAgentWithKey();
        String id = s.agent().getId().toString();
        JsonNode schemas = j.get("components").get("schemas");
        assertMatchesSchema(schemas, "MessageResponse", api.post("/api/v1/agents/" + id + "/messages", s.token(), "{\"message\":\"hello there\"}").json());
        JsonNode check = api.post("/api/v1/guardrails/check", s.token(), "{\"agentId\":\"" + id + "\",\"text\":\"hello\"}").json();
        assertMatchesSchema(schemas, "CheckResponse", check);
        assertMatchesSchema(schemas, "AgentList", api.get("/api/v1/agents", s.token()).json());
        assertMatchesSchema(schemas, "ApiError", api.get("/api/v1/agents", "bad").json());
    }

    private static void assertMatchesSchema(JsonNode schemas, String name, JsonNode actual) {
        Set<String> promised = new java.util.HashSet<>(schemas.get(name).get("properties").propertyNames());
        Set<String> got = new java.util.HashSet<>(actual.propertyNames());
        assertThat(got).as(name + " fields").isSubsetOf(promised);
        JsonNode required = schemas.get(name).get("required");
        if (required != null) {
            required.forEach(r -> assertThat(got).as(name + " required " + r.asString()).contains(r.asString()));
        }
    }
}
