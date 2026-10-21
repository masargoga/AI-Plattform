package com.agenticpilot.common;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentRepository;
import com.agenticpilot.agent.AgentStatus;
import com.agenticpilot.agent.Autonomy;
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
import java.util.EnumSet;
import java.util.Set;
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

    DemoData(AppUserRepository users, AgentRepository agents, SkillRepository skills, DataSourceRepository sources) {
        this.users = users;
        this.agents = agents;
        this.skills = skills;
        this.sources = sources;
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

        agent(owner, "Support Concierge", "Answers customer questions and hands off tricky cases.",
                "You are Support Concierge for Northwind. Greet customers by name, answer from the Help Center and hand off to a person when the customer is upset or the answer is missing. Never promise a refund.",
                AgentStatus.ACTIVE, Autonomy.ACT, ModelTier.BALANCED, now.minus(Duration.ofMinutes(4)), 1284,
                Set.of(answer, summarize, escalate, ticket), Set.of(help, tickets, orders));
        agent(owner, "Research Scout", "Builds one-page briefs on markets and competitors.",
                "You are Research Scout. Plan three searches per question, cite every source and say clearly where sources disagree. Keep briefs to one page.",
                AgentStatus.ACTIVE, Autonomy.ASK, ModelTier.DEEP, now.minus(Duration.ofHours(1)), 212,
                Set.of(research, summarize, plain), Set.of(reports, wiki));
        agent(admin, "Contract Reviewer", "Flags risky clauses and explains them in plain language.",
                "You are Contract Reviewer for the legal team. Compare every contract with the playbook, flag deviations by clause number and explain each in plain words. You do not give legal advice.",
                AgentStatus.ACTIVE, Autonomy.ASK, ModelTier.DEEP, now.minus(Duration.ofHours(3)), 87,
                Set.of(clause, plain), Set.of(contracts));
        agent(owner, "Weekly Reporter", "Drafts the Friday status update from tickets and wiki changes.",
                "You are Weekly Reporter. Every Friday, summarize the week for the leadership team in under 120 words. Lead with the single biggest change.",
                AgentStatus.PAUSED, Autonomy.ACT, ModelTier.FAST, now.minus(Duration.ofDays(5)), 36,
                Set.of(weekly, summarize), Set.of(tickets, wiki));
        log.info("Loaded demo data: 8 skills, 6 data sources, 4 agents");
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
        for (int i = 1; i < revision; i++) {
            s.raiseRevision();
        }
        return skills.save(s);
    }

    private DataSource source(AppUser by, String name, SourceKind kind, String location, int count, Instant synced) {
        DataSource d = new DataSource(name, kind, location, by);
        d.markSynced(count, synced);
        return sources.save(d);
    }

    private void agent(AppUser owner, String name, String job, String instructions, AgentStatus status, Autonomy autonomy,
                       ModelTier tier, Instant lastRun, int runs, Set<Skill> skillSet, Set<DataSource> sourceSet) {
        Agent a = new Agent(name, owner);
        a.setJob(job);
        a.setInstructions(instructions);
        a.setStatus(status);
        a.setAutonomy(autonomy);
        a.setModelTier(tier);
        a.recordRun(lastRun, runs);
        a.getSkills().addAll(skillSet);
        a.getSources().addAll(sourceSet);
        agents.save(a);
    }
}
