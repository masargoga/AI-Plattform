package com.agenticpilot.ui;

import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;

@Route(value = "skills", layout = MainLayout.class)
@PageTitle("AgenticPilot")
@RolesAllowed({"ADMIN", "AGENT_OWNER"})
public class SkillsView extends PlaceholderView {

    public SkillsView() {
        super("nav.skills", "skills.hint");
    }
}
