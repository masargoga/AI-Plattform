package com.agenticpilot.ui;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;

/** Base for the M0 screens: a heading and an empty-state hint until the real screen lands. */
abstract class PlaceholderView extends Div {

    PlaceholderView(String titleKey, String hintKey) {
        addClassName("ap-page");
        H1 title = new H1(getTranslation(titleKey));
        Paragraph sub = new Paragraph(getTranslation(hintKey));
        sub.addClassName("ap-sub");
        Div empty = new Div(new Paragraph(getTranslation("placeholder.comingSoon")));
        empty.addClassName("ap-empty");
        add(title, sub, empty);
    }
}
