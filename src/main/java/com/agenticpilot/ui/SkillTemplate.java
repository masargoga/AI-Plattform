package com.agenticpilot.ui;

import com.agenticpilot.skill.OutputFormat;
import com.agenticpilot.skill.SkillService;
import com.agenticpilot.skill.SkillType;
import com.agenticpilot.skill.Tool;
import java.util.EnumSet;
import java.util.Set;

/** Starting points in the new-skill sheet (texts are in the message bundle under skill.template.*). */
enum SkillTemplate {
    BLANK("blank", SkillType.INSTRUCTION, OutputFormat.PLAIN_TEXT, EnumSet.noneOf(Tool.class)),
    SUMMARIZE("sum", SkillType.INSTRUCTION, OutputFormat.BULLETS, EnumSet.noneOf(Tool.class)),
    EXTRACT("ext", SkillType.INSTRUCTION, OutputFormat.TABLE, EnumSet.noneOf(Tool.class)),
    REVIEW("rev", SkillType.WORKFLOW, OutputFormat.BULLETS, EnumSet.of(Tool.SEARCH_DOCUMENTS));

    final String key;
    final SkillType type;
    final OutputFormat output;
    final Set<Tool> tools;

    SkillTemplate(String key, SkillType type, OutputFormat output, Set<Tool> tools) {
        this.key = key;
        this.type = type;
        this.output = output;
        this.tools = tools;
    }

    SkillService.Content content(java.util.function.Function<String, String> t) {
        String p = "skill.template." + key + ".";
        return new SkillService.Content(t.apply(p + "name"), type, t.apply(p + "desc"), t.apply(p + "trigger"),
                t.apply(p + "instructions"), tools, output);
    }
}
