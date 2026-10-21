package com.agenticpilot.ui;

import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;

@Route(value = "agents", layout = MainLayout.class)
@PageTitle("AgenticPilot")
@RolesAllowed({"ADMIN", "AGENT_OWNER"})
public class AgentsView extends PlaceholderView {

    public AgentsView() {
        super("nav.agents", "agents.hint");
    }
}
