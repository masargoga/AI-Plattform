package com.agenticpilot.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Nav;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.router.RouterLayout;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.PermitAll;
import com.agenticpilot.user.AppUser;
import com.agenticpilot.user.AppUserRepository;

/**
 * Left rail on wide screens, top bar plus bottom tab bar below 860 px (see styles in layout.css).
 */
@PermitAll
public class MainLayout extends Div implements RouterLayout {

    private record Entry(String path, String labelKey, VaadinIcon icon, Class<? extends Component> view) {
    }

    private static final java.util.List<Entry> ENTRIES = java.util.List.of(
            new Entry("agents", "nav.agents", VaadinIcon.USER_STAR, AgentsView.class),
            new Entry("skills", "nav.skills", VaadinIcon.MAGIC, SkillsView.class),
            new Entry("sources", "nav.sources", VaadinIcon.DATABASE, SourcesView.class),
            new Entry("policies", "nav.policies", VaadinIcon.SHIELD, PoliciesView.class));

    private final Div content = new Div();

    public MainLayout(AuthenticationContext auth, AppUserRepository users) {
        addClassName("ap-app");

        AppUser user = auth.getPrincipalName().flatMap(users::findByUsername).orElse(null);

        add(rail(auth, user), topBar(auth, user), content, tabBar());
        content.addClassName("ap-content");
        content.setId("ap-content");
    }

    private Component brand() {
        Div brand = new Div();
        brand.addClassName("ap-brand");
        Div logo = new Div();
        logo.addClassName("ap-logo");
        logo.getElement().setProperty("innerHTML",
                "<svg viewBox='0 0 28 28' aria-hidden='true'><path d='M7 9h14M7 14h9M7 19h12' stroke-width='2.4' stroke-linecap='round'/></svg>");
        brand.add(logo, new Span(getTranslation("app.name")));
        return brand;
    }

    private Component rail(AuthenticationContext auth, AppUser user) {
        Div rail = new Div();
        rail.addClassName("ap-rail");

        SideNav nav = new SideNav();
        nav.getElement().setAttribute("aria-label", getTranslation("nav.main"));
        for (Entry e : ENTRIES) {
            nav.addItem(new SideNavItem(getTranslation(e.labelKey()), e.view(), new Icon(e.icon())));
        }

        Div tagline = new Div(new Span(getTranslation("app.tagline")));
        tagline.addClassName("ap-tagline");
        rail.add(brand(), nav, tagline, who(auth, user));
        return rail;
    }

    private Component who(AuthenticationContext auth, AppUser user) {
        Div who = new Div();
        who.addClassName("ap-who");
        if (user != null) {
            Span name = new Span(user.getDisplayName());
            name.getElement().getStyle().set("font-weight", "600");
            Span role = new Span(getTranslation("role." + user.getRole().name()));
            who.add(name, role);
        }
        Button logout = new Button(getTranslation("nav.logout"), new Icon(VaadinIcon.SIGN_OUT), e -> auth.logout());
        logout.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        who.add(logout);
        return who;
    }

    private Component topBar(AuthenticationContext auth, AppUser user) {
        Div top = new Div();
        top.addClassName("ap-topbar");
        Button logout = new Button(new Icon(VaadinIcon.SIGN_OUT), e -> auth.logout());
        logout.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        logout.setAriaLabel(getTranslation("nav.logout"));
        top.add(brand(), logout);
        return top;
    }

    private Component tabBar() {
        Nav tabs = new Nav();
        tabs.addClassName("ap-tabbar");
        tabs.getElement().setAttribute("aria-label", getTranslation("nav.main"));
        for (Entry e : ENTRIES) {
            RouterLink link = new RouterLink(e.view());
            link.addClassName("ap-tab");
            Span label = new Span(getTranslation(e.labelKey() + ".short"));
            link.add(new Icon(e.icon()), label);
            link.getElement().setAttribute("aria-label", getTranslation(e.labelKey()));
            tabs.add(link);
        }
        return tabs;
    }

    @Override
    public void showRouterLayoutContent(com.vaadin.flow.component.HasElement contentView) {
        content.removeAll();
        if (contentView != null) {
            content.getElement().appendChild(contentView.getElement());
        }
    }
}
