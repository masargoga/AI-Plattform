package com.agenticpilot.ui;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.login.LoginI18n;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

@Route("login")
@PageTitle("AgenticPilot")
@AnonymousAllowed
public class LoginView extends Div implements BeforeEnterObserver {

    private final LoginForm form = new LoginForm();

    public LoginView() {
        addClassName("ap-login");
        H1 title = new H1(getTranslation("app.name"));
        Paragraph tagline = new Paragraph(getTranslation("app.tagline"));
        tagline.addClassName("ap-sub");

        LoginI18n i18n = LoginI18n.createDefault();
        i18n.getForm().setTitle(getTranslation("login.title"));
        i18n.getForm().setUsername(getTranslation("login.username"));
        i18n.getForm().setPassword(getTranslation("login.password"));
        i18n.getForm().setSubmit(getTranslation("login.submit"));
        i18n.getErrorMessage().setTitle(getTranslation("login.error.title"));
        i18n.getErrorMessage().setMessage(getTranslation("login.error.message"));
        form.setI18n(i18n);
        form.setAction("login");
        form.setForgotPasswordButtonVisible(false);

        add(title, tagline, form);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (event.getLocation().getQueryParameters().getParameters().containsKey("error")) {
            form.setError(true);
        }
    }
}
