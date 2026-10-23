package com.agenticpilot.common;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentRepository;
import com.agenticpilot.agent.AgentStatus;
import com.agenticpilot.agent.Autonomy;
import com.agenticpilot.audit.AuditEntry;
import com.agenticpilot.audit.AuditEntryRepository;
import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.compliance.ComplianceRecord;
import com.agenticpilot.compliance.ComplianceRecordRepository;
import com.agenticpilot.compliance.RecordStatus;
import com.agenticpilot.compliance.SubjectKind;
import com.agenticpilot.compliance.SubjectRequest;
import com.agenticpilot.compliance.SubjectRequestRepository;
import com.agenticpilot.guardrail.AgentGuardrail;
import com.agenticpilot.guardrail.AgentGuardrailRepository;
import com.agenticpilot.guardrail.ApprovalAction;
import com.agenticpilot.guardrail.CustomRule;
import com.agenticpilot.guardrail.CustomRuleRepository;
import com.agenticpilot.guardrail.PersonalDataMode;
import com.agenticpilot.guardrail.Region;
import com.agenticpilot.guardrail.Retention;
import com.agenticpilot.guardrail.RuleAction;
import com.agenticpilot.guardrail.RuleScope;
import com.agenticpilot.guardrail.RuleSnapshot;
import com.agenticpilot.guardrail.RuleVersion;
import com.agenticpilot.guardrail.RuleVersionRepository;
import com.agenticpilot.guardrail.SpecialDataMode;
import com.agenticpilot.guardrail.VersionStatus;
import com.agenticpilot.agent.ModelTier;
import com.agenticpilot.skill.OutputFormat;
import com.agenticpilot.skill.Skill;
import com.agenticpilot.skill.SkillRepository;
import com.agenticpilot.skill.SkillType;
import com.agenticpilot.skill.Tool;
import com.agenticpilot.source.DataSource;
import com.agenticpilot.source.DataSourceRepository;
import com.agenticpilot.source.SourceKind;
import com.agenticpilot.user.AppUser;
import com.agenticpilot.user.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Demo skills, sources and agents taken from the click-dummy. Runs once, only when no agents exist yet. */
@Component
@Order(20)
@ConditionalOnProperty(name = "agenticpilot.demo-data", havingValue = "true")
class DemoData implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoData.class);

    private final AppUserRepository users;
    private final AgentRepository agents;
    private final SkillRepository skills;
    private final DataSourceRepository sources;
    private final AgentGuardrailRepository guardrails;
    private final CustomRuleRepository rules;
    private final RuleVersionRepository ruleVersions;
    private final AuditEntryRepository audit;
    private final ComplianceRecordRepository records;
    private final SubjectRequestRepository requests;
    private final JsonMapper json = JsonMapper.builder().build();

    DemoData(AppUserRepository users, AgentRepository agents, SkillRepository skills, DataSourceRepository sources,
             AgentGuardrailRepository guardrails, CustomRuleRepository rules, RuleVersionRepository ruleVersions,
             AuditEntryRepository audit, ComplianceRecordRepository records, SubjectRequestRepository requests) {
        this.users = users;
        this.agents = agents;
        this.skills = skills;
        this.sources = sources;
        this.guardrails = guardrails;
        this.rules = rules;
        this.ruleVersions = ruleVersions;
        this.audit = audit;
        this.records = records;
        this.requests = requests;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (agents.count() > 0 || skills.count() > 0 || sources.count() > 0) {
            return;
        }
        AppUser owner = users.findByUsername("owner").orElse(null);
        AppUser admin = users.findByUsername("admin").orElse(null);
        if (owner == null || admin == null) {
            return;
        }
        Instant now = Instant.now();

        Skill answer = skill(owner, "Answer from docs", SkillType.INSTRUCTION, 3,
                "Answers questions using only the attached documents and names the page it used.",
                "A user asks a factual question about a product, policy or process.",
                "Search the attached sources before answering.\nUse only what you find. If the documents do not cover the question, say so.\nEnd every answer with the title of the page you used.\nKeep answers under 120 words unless asked for more.",
                OutputFormat.PLAIN_TEXT, Tool.SEARCH_DOCUMENTS);
        Skill summarize = skill(admin, "Summarize thread", SkillType.INSTRUCTION, 2,
                "Turns a long conversation into decisions, owners and open questions.",
                "A message thread or ticket has more than ten replies.",
                "Read the whole thread first.\nList the decisions that were made, who owns each next step and what is still open.\nIgnore greetings and small talk.",
                OutputFormat.BULLETS);
        Skill escalate = skill(owner, "Escalate to human", SkillType.WORKFLOW, 5,
                "Hands off to a teammate when confidence is low or the customer is upset, with a short note.",
                "The customer is angry, asks for a person, or the documents do not answer the question.",
                "1. Apologize once, in one sentence.\n2. Summarize the problem and what was already tried.\n3. Create a ticket with the summary.\n4. Tell the customer when a person will reply.",
                OutputFormat.PLAIN_TEXT, Tool.CREATE_TICKET, Tool.SEND_EMAIL);
        Skill research = skill(admin, "Web research brief", SkillType.WORKFLOW, 4,
                "Plans searches, reads sources and writes a one-page brief with links.",
                "The user asks for a brief, a comparison or a market overview.",
                "1. Break the question into three searches.\n2. Read the top sources for each.\n3. Note where sources disagree.\n4. Write one page: summary, key facts, open questions.",
                OutputFormat.BULLETS, Tool.BROWSE_WEB, Tool.SEARCH_DOCUMENTS);
        Skill clause = skill(admin, "Clause check", SkillType.INSTRUCTION, 2,
                "Flags contract clauses that deviate from your playbook.",
                "A contract or an amendment is attached for review.",
                "Compare every clause with the playbook in the attached sources.\nFlag a clause when notice periods, renewal terms or liability caps differ.\nQuote the clause number and say what the playbook expects.",
                OutputFormat.TABLE, Tool.SEARCH_DOCUMENTS);
        Skill plain = skill(owner, "Plain-language rewrite", SkillType.INSTRUCTION, 1,
                "Rewrites legal or technical text so anyone can follow it.",
                "The user asks what a clause or a technical passage means.",
                "Rewrite in short sentences.\nReplace jargon with everyday words.\nKeep every number, date and name unchanged.",
                OutputFormat.PLAIN_TEXT);
        Skill weekly = skill(owner, "Draft weekly update", SkillType.WORKFLOW, 3,
                "Collects closed tickets and wiki changes into a short Friday update.",
                "It is Friday afternoon, or the user asks for a status update.",
                "1. Count tickets closed and escalated this week.\n2. List wiki pages that changed.\n3. Pick the single biggest change.\n4. Write the update in under 120 words.",
                OutputFormat.BULLETS, Tool.READ_SHEETS, Tool.SEARCH_DOCUMENTS);
        Skill ticket = skill(admin, "Create support ticket", SkillType.TOOL, 1,
                "Opens a ticket in the helpdesk with a title, priority and summary.",
                "A request needs a human follow-up that is not urgent.",
                "Create a ticket with a title under 8 words.\nSet priority to high only if the customer cannot use the product.\nPaste the conversation summary into the description.",
                OutputFormat.JSON, Tool.CREATE_TICKET);

        DataSource help = source(admin, "Help Center", SourceKind.WEBSITE, "https://help.northwind.example", 412, now.minus(Duration.ofHours(2)));
        DataSource contracts = source(admin, "Contracts 2026", SourceKind.UPLOAD, "Legal contracts 2026", 138, now.minus(Duration.ofMinutes(35)));
        DataSource wiki = source(owner, "Product Wiki", SourceKind.WEBSITE, "https://wiki.northwind.example/product", 96, now.minus(Duration.ofDays(1)));
        DataSource reports = source(owner, "Market reports", SourceKind.UPLOAD, "Uploaded PDFs", 54, now.minus(Duration.ofDays(6)));
        DataSource orders = source(admin, "Orders export", SourceKind.UPLOAD, "Orders CSV exports", 12400, now.minus(Duration.ofMinutes(10)));
        DataSource tickets = source(owner, "Helpdesk tickets", SourceKind.UPLOAD, "Ticket exports, last 90 days", 3870, now.minus(Duration.ofHours(1)));

        AppUser admin2 = users.findByUsername("admin2").orElse(admin);
        Agent concierge = agent(owner, "Support Concierge", "Answers customer questions and hands off tricky cases.",
                "You are Support Concierge for Northwind. Greet customers by name, answer from the Help Center and hand off to a person when the customer is upset or the answer is missing. Never promise a refund.",
                AgentStatus.ACTIVE, Autonomy.ACT, ModelTier.BALANCED, now.minus(Duration.ofMinutes(4)), 1284,
                Set.of(answer, summarize, escalate, ticket), Set.of(help, tickets, orders));
        Agent scout = agent(owner, "Research Scout", "Builds one-page briefs on markets and competitors.",
                "You are Research Scout. Plan three searches per question, cite every source and say clearly where sources disagree. Keep briefs to one page.",
                AgentStatus.ACTIVE, Autonomy.ASK, ModelTier.DEEP, now.minus(Duration.ofHours(1)), 212,
                Set.of(research, summarize, plain), Set.of(reports, wiki));
        Agent reviewer = agent(admin, "Contract Reviewer", "Flags risky clauses and explains them in plain language.",
                "You are Contract Reviewer for the legal team. Compare every contract with the playbook, flag deviations by clause number and explain each in plain words. You do not give legal advice.",
                AgentStatus.ACTIVE, Autonomy.ASK, ModelTier.DEEP, now.minus(Duration.ofHours(3)), 87,
                Set.of(clause, plain), Set.of(contracts));
        Agent reporter = agent(owner, "Weekly Reporter", "Drafts the Friday status update from tickets and wiki changes.",
                "You are Weekly Reporter. Every Friday, summarize the week for the leadership team in under 120 words. Lead with the single biggest change.",
                AgentStatus.PAUSED, Autonomy.ACT, ModelTier.FAST, now.minus(Duration.ofDays(5)), 36,
                Set.of(weekly, summarize), Set.of(tickets, wiki));
        guard(concierge, PersonalDataMode.MASK, SpecialDataMode.BLOCK, Region.EU, Retention.DAYS_90, true, true, 50,
                "Answer customer questions about orders, plans and billing. Not used for marketing.", ApprovalAction.EMAIL, ApprovalAction.REFUND);
        guard(scout, PersonalDataMode.MASK, SpecialDataMode.BLOCK, Region.ANY, Retention.UNLIMITED, false, false, 200,
                "Research on public markets and competitors using public sources.");
        guard(reviewer, PersonalDataMode.MASK, SpecialDataMode.REVIEW, Region.EU, Retention.YEAR_1, true, true, 10, "", ApprovalAction.SHARE);
        guard(reporter, PersonalDataMode.MASK, SpecialDataMode.BLOCK, Region.EU, Retention.DAYS_30, true, false, 50,
                "Summarize internal tickets and wiki changes for leadership.", ApprovalAction.EMAIL);

        demoRules(admin, admin2, owner, concierge, now);
        demoAudit(concierge, reviewer, reporter, scout, now);
        demoCompliance();
        log.info("Loaded demo data: 8 skills, 6 data sources, 4 agents, 3 rules, audit log and compliance records");
    }

    private Skill skill(AppUser by, String name, SkillType type, int revision, String description, String trigger,
                        String instructions, OutputFormat format, Tool... tools) {
        Skill s = new Skill(name, by);
        s.setType(type);
        s.setDescription(description);
        s.setTriggerText(trigger);
        s.setInstructions(instructions);
        s.setOutputFormat(format);
        s.setTools(tools.length == 0 ? EnumSet.noneOf(Tool.class) : EnumSet.of(tools[0], tools));
        s.setLiveVersion(revision);
        s.setLifecycle(com.agenticpilot.governance.Lifecycle.APPROVED);
        return skills.save(s);
    }

    private DataSource source(AppUser by, String name, SourceKind kind, String location, int count, Instant synced) {
        DataSource d = new DataSource(name, kind, location, by);
        d.markSynced(count, synced);
        return sources.save(d);
    }

    private Agent agent(AppUser owner, String name, String job, String instructions, AgentStatus status, Autonomy autonomy,
                       ModelTier tier, Instant lastRun, int runs, Set<Skill> skillSet, Set<DataSource> sourceSet) {
        Agent a = new Agent(name, owner);
        a.setJob(job);
        a.setGoal(job);
        a.setInstructions(instructions);
        a.setStatus(status);
        a.setAutonomy(autonomy);
        a.setModelTier(tier);
        a.recordRun(lastRun, runs);
        a.getSkills().addAll(skillSet);
        a.getSources().addAll(sourceSet);
        a.setLiveVersion(1);
        a.setLifecycle(com.agenticpilot.governance.Lifecycle.APPROVED);
        return agents.save(a);
    }

    private void guard(Agent agent, PersonalDataMode pii, SpecialDataMode special, Region region, Retention retention,
                       boolean injection, boolean review, int limit, String purpose, ApprovalAction... approvals) {
        AgentGuardrail g = AgentGuardrail.defaultsFor(agent, retention);
        g.setPersonalData(pii);
        g.setSpecialData(special);
        g.setRegion(region);
        g.setHiddenInstructionProtection(injection);
        g.setHumanReview(review);
        g.setMaxRecords(limit);
        g.setPurpose(purpose);
        g.setApprovals(approvals.length == 0 ? java.util.EnumSet.noneOf(ApprovalAction.class) : java.util.EnumSet.of(approvals[0], approvals));
        guardrails.save(g);
    }

    private void demoRules(AppUser admin, AppUser admin2, AppUser owner, Agent concierge, Instant now) {
        RuleSnapshot legalV1 = new RuleSnapshot("No legal advice", "lawsuit, sue", RuleAction.BLOCK, "I cannot give legal advice. Please talk to a lawyer.", RuleScope.ALL, Set.of(), "Can I sue you over this late order?");
        RuleSnapshot legalV2 = new RuleSnapshot("No legal advice", "lawsuit, sue, legal advice", RuleAction.BLOCK, "I cannot give legal advice. Please talk to a lawyer.", RuleScope.ALL, Set.of(), "Can I sue you over this late order?");
        CustomRule legal = live(legalV2, admin, 2);
        approved(legal, 1, legalV1, admin, admin2, "Created.", now.minus(Duration.ofDays(19)));
        approved(legal, 2, legalV2, admin2, admin, "Added \u201clegal advice\u201d as a keyword.", now.minus(Duration.ofDays(7)));

        RuleSnapshot numbers = new RuleSnapshot("Hide customer numbers", "/KD-\\d{4,}/", RuleAction.MASK, "", RuleScope.ALL, Set.of(), "My customer number is KD-48213, where is my order?");
        CustomRule hide = live(numbers, admin2, 1);
        approved(hide, 1, numbers, admin2, admin, "Created to protect customer numbers.", now.minus(Duration.ofDays(16)));

        RuleSnapshot discountV1 = new RuleSnapshot("Discounts need approval", "discount, coupon", RuleAction.APPROVE,
                "A discount was requested. Please check the customer history.", RuleScope.SELECTED, Set.of(concierge.getId()), "Can I get a discount on my next order?");
        RuleSnapshot discountV2 = new RuleSnapshot("Discounts need approval", "discount, coupon, rebate", RuleAction.APPROVE,
                "A discount was requested. Please check the customer history.", RuleScope.SELECTED, Set.of(concierge.getId()), "Can I get a discount on my next order?");
        CustomRule discount = live(discountV1, owner, 1);
        approved(discount, 1, discountV1, owner, admin, "Created for Support Concierge.", now.minus(Duration.ofDays(12)));
        ruleVersions.save(new RuleVersion(discount.getId(), 2, json.writeValueAsString(discountV2), owner, "Customers also ask for rebates."));
    }

    private CustomRule live(RuleSnapshot snapshot, AppUser creator, int liveVersion) {
        CustomRule rule = new CustomRule(snapshot, creator);
        rule.setLiveVersion(liveVersion);
        return rules.save(rule);
    }

    private void approved(CustomRule rule, int number, RuleSnapshot snapshot, AppUser author, AppUser approver, String note, Instant decided) {
        RuleVersion v = new RuleVersion(rule.getId(), number, json.writeValueAsString(snapshot), author, note);
        v.decide(VersionStatus.APPROVED, approver, decided);
        ruleVersions.save(v);
    }

    private void demoAudit(Agent concierge, Agent reviewer, Agent reporter, Agent scout, Instant now) {
        entry(now.minus(Duration.ofMinutes(20)), concierge, "Answered a billing question from Help Center", AuditOutcome.ALLOWED);
        entry(now.minus(Duration.ofMinutes(32)), concierge, "Customer email address replaced before processing", AuditOutcome.MASKED);
        entry(now.minus(Duration.ofMinutes(97)), reviewer, "Sharing a redlined contract outside the company", AuditOutcome.NEEDS_APPROVAL);
        entry(now.minus(Duration.ofMinutes(124)), concierge, "Message mentioned a medical condition", AuditOutcome.BLOCKED);
        entry(now.minus(Duration.ofMinutes(170)), reporter, "Drafted the weekly update from 41 tickets", AuditOutcome.ALLOWED);
        entry(now.minus(Duration.ofMinutes(202)), scout, "Read 3 reports from Market reports", AuditOutcome.ALLOWED);
        entry(now.minus(Duration.ofMinutes(261)), concierge, "Refund of EUR 39.90 prepared, waiting for a person", AuditOutcome.NEEDS_APPROVAL);
        entry(now.minus(Duration.ofMinutes(307)), reviewer, "IBAN in a supplier contract masked in the summary", AuditOutcome.MASKED);
    }

    private void entry(Instant when, Agent agent, String event, AuditOutcome outcome) {
        audit.save(new AuditEntry(when, agent.getId(), agent.getName(), "demo", event, outcome, null, null, null));
    }

    private void demoCompliance() {
        LocalDate today = LocalDate.now();
        records.save(new ComplianceRecord("Record of processing activities", "Data protection officer", "updated", today.minusDays(9), RecordStatus.DONE));
        records.save(new ComplianceRecord("Data protection impact assessment, Support Concierge", "Legal", "due", today.plusDays(40), RecordStatus.IN_PROGRESS));
        records.save(new ComplianceRecord("Data processing agreement with the model provider", "Legal", "signed", today.minusDays(48), RecordStatus.DONE));
        records.save(new ComplianceRecord("Privacy notice shown to chat users", "Support lead", "not started", null, RecordStatus.TODO));
        requests.save(new SubjectRequest(SubjectKind.ACCESS, "A. Keller", today.plusDays(12)));
        requests.save(new SubjectRequest(SubjectKind.DELETION, "M. Brandt", today.plusDays(3)));
    }
}
