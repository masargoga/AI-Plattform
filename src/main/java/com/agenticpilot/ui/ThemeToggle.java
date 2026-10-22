package com.agenticpilot.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;

/**
 * Switches between the light and the dark theme. Without a choice the theme follows the system. The choice is kept in the
 * browser (localStorage) and applied as data-theme on the html element, which the style sheet reads.
 */
class ThemeToggle extends Button {

    private static final String EFFECTIVE = "const r=document.documentElement;"
            + "let t=null;try{t=localStorage.getItem('ap-theme')}catch(e){}"
            + "if(t==='light'||t==='dark'){r.setAttribute('data-theme',t)}"
            + "return r.getAttribute('data-theme')||(matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light');";

    private static final String TOGGLE = "const r=document.documentElement;"
            + "const cur=r.getAttribute('data-theme')||(matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light');"
            + "const next=cur==='dark'?'light':'dark';r.setAttribute('data-theme',next);"
            + "try{localStorage.setItem('ap-theme',next)}catch(e){}return next;";

    ThemeToggle(boolean withLabel) {
        addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        addClassName("ap-theme-toggle");
        showFor("light", withLabel);
        addClickListener(e -> getElement().executeJs(TOGGLE).then(String.class, next -> showFor(next, withLabel)));
        this.withLabel = withLabel;
    }

    private final boolean withLabel;

    @Override
    protected void onAttach(AttachEvent event) {
        super.onAttach(event);
        getElement().executeJs(EFFECTIVE).then(String.class, current -> showFor(current, withLabel));
    }

    /** Shows what a click would switch to. */
    private void showFor(String current, boolean label) {
        boolean dark = "dark".equals(current);
        String text = getTranslation(dark ? "theme.toLight" : "theme.toDark");
        setIcon(new Icon(dark ? VaadinIcon.SUN_O : VaadinIcon.MOON_O));
        setAriaLabel(text);
        getElement().setAttribute("title", text);
        setText(label ? text : "");
    }
}
