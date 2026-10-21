package com.agenticpilot.ui;

import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;

@Route(value = "policies", layout = MainLayout.class)
@PageTitle("AgenticPilot")
@RolesAllowed({"ADMIN", "AGENT_OWNER"})
public class PoliciesView extends PlaceholderView {

    public PoliciesView() {
        super("nav.policies", "policies.hint");
    }
}
