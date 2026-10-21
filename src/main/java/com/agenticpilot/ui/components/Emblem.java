package com.agenticpilot.ui.components;

import com.agenticpilot.agent.Agent;
import com.vaadin.flow.component.html.Span;

/** Coloured initials for an agent. The colour is derived from the id, so it stays the same. */
public class Emblem extends Span {

    public Emblem(Agent agent, String size) {
        addClassNames("ap-emb", "ap-e" + (1 + Math.floorMod(agent.getId().hashCode(), 4)));
        if (size != null) {
            addClassName("ap-" + size);
        }
        setText(initials(agent.getName()));
        getElement().setAttribute("aria-hidden", "true");
    }

    static String initials(String name) {
        StringBuilder sb = new StringBuilder();
        for (String w : name.strip().split("\\s+")) {
            if (!w.isEmpty() && sb.length() < 2) {
                sb.appendCodePoint(w.codePointAt(0));
            }
        }
        return sb.length() == 0 ? "?" : sb.toString().toUpperCase();
    }
}
