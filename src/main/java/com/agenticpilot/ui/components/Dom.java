package com.agenticpilot.ui.components;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasComponents;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;

/** Small factory methods for the click-dummy markup (native elements styled by app.css). */
public final class Dom {

    private Dom() {
    }

    public static Div div(String classes, Component... children) {
        Div d = new Div(children);
        addClasses(d, classes);
        return d;
    }

    public static Span span(String classes, String text) {
        Span s = new Span(text);
        addClasses(s, classes);
        return s;
    }

    public static Span span(String classes, Component... children) {
        Span s = new Span(children);
        addClasses(s, classes);
        return s;
    }

    public static NativeButton button(String classes, String text, Runnable onClick) {
        NativeButton b = new NativeButton(text);
        b.getElement().setAttribute("type", "button");
        addClasses(b, classes);
        if (onClick != null) {
            b.addClickListener(e -> onClick.run());
        }
        return b;
    }

    public static NativeButton button(String classes, VaadinIcon icon, String text, Runnable onClick) {
        NativeButton b = button(classes, null, onClick);
        b.add(icon(icon, 16), new Span(text));
        return b;
    }

    public static NativeButton iconButton(String classes, VaadinIcon icon, String ariaLabel, Runnable onClick) {
        NativeButton b = button(classes, null, onClick);
        b.add(icon(icon, 16));
        b.getElement().setAttribute("aria-label", ariaLabel);
        b.getElement().setAttribute("title", ariaLabel);
        return b;
    }

    /** A control row: title and description on the left, the control (usually a switch) on the right. */
    public static Div control(String title, String description, Component control) {
        Span t = new Span(title);
        t.getStyle().set("font-weight", "600");
        Span d = new Span(description);
        d.addClassName("ap-hint");
        d.getStyle().set("display", "block");
        return div("ap-ctl", div(null, t, d), control);
    }

    /** A line with a shield icon, used for "workspace rule" and "only admins" notes. */
    public static Div lock(String text) {
        return div("ap-lock", icon(VaadinIcon.SHIELD, 14), new Span(text));
    }

    public static Icon icon(VaadinIcon icon, int size) {
        Icon i = icon.create();
        i.setSize(size + "px");
        i.getElement().setAttribute("aria-hidden", "true");
        return i;
    }

    public static void addClasses(Component c, String classes) {
        if (classes != null && !classes.isBlank()) {
            for (String cls : classes.trim().split("\\s+")) {
                c.addClassName(cls);
            }
        }
    }

    public static void setVisible(Component c, boolean visible) {
        c.setVisible(visible);
    }

    public static <T extends Component & HasComponents> T with(T parent, Component... children) {
        parent.add(children);
        return parent;
    }
}
