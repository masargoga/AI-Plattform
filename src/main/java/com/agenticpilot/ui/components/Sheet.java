package com.agenticpilot.ui.components;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.VaadinIcon;

/** A dialog that is a bottom sheet on phones and a centred dialog from 700 px (styles in app.css). */
public class Sheet extends Dialog {

    private final Div body = Dom.div("ap-sheet-body");
    private final Div footer = Dom.div("ap-sheet-f");
    private final Div header = Dom.div("ap-sheet-h");

    public Sheet(String title, String subtitle) {
        addClassName("ap-sheet-overlay");
        setOverlayRole("dialog");
        setHeaderTitle(null);
        setCloseOnOutsideClick(true);
        setModal(true);
        setDraggable(false);
        setResizable(false);
        getElement().setAttribute("aria-label", title);

        Div titles = new Div(new H2(title));
        if (subtitle != null) {
            Paragraph p = new Paragraph(subtitle);
            p.addClassName("ap-hint");
            titles.add(p);
        }
        header.add(titles, Dom.iconButton("ap-iconbtn", VaadinIcon.CLOSE_SMALL, getTranslation("action.close"), this::close));
        super.add(Dom.div("ap-sheet-content", header, body, footer));
    }

    public Div body() {
        return body;
    }

    public Div footer() {
        return footer;
    }

    public void setBody(Component... content) {
        body.removeAll();
        body.add(content);
    }

    public void setFooter(Component... content) {
        footer.removeAll();
        footer.add(content);
    }
}
