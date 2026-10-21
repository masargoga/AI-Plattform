package com.agenticpilot.ui;

import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;

@Route(value = "sources", layout = MainLayout.class)
@PageTitle("AgenticPilot")
@RolesAllowed({"ADMIN", "AGENT_OWNER"})
public class SourcesView extends PlaceholderView {

    public SourcesView() {
        super("nav.sources", "sources.hint");
    }
}
