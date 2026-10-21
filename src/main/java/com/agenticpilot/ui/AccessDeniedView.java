package com.agenticpilot.ui;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.router.AccessDeniedException;
import com.vaadin.flow.router.ErrorParameter;
import com.vaadin.flow.router.HasErrorParameter;
import com.vaadin.flow.router.PageTitle;
import jakarta.servlet.http.HttpServletResponse;

/** Shown when a signed-in user opens a view their role does not allow. */
@PageTitle("AgenticPilot")
public class AccessDeniedView extends Div implements HasErrorParameter<AccessDeniedException> {

    @Override
    public int setErrorParameter(com.vaadin.flow.router.BeforeEnterEvent event, ErrorParameter<AccessDeniedException> parameter) {
        addClassName("ap-page");
        add(new H1(getTranslation("denied.title")), new Paragraph(getTranslation("denied.message")));
        return HttpServletResponse.SC_FORBIDDEN;
    }
}
