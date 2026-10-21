package com.agenticpilot.ui.components;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Input;
import com.vaadin.flow.component.html.NativeLabel;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.data.value.ValueChangeMode;

/** Label, control and optional hint in the click-dummy's .field layout. */
public class FormField extends Div {

    public FormField(String labelText, String forId, Component control, String hint) {
        addClassName("ap-field");
        if (forId != null) {
            NativeLabel l = new NativeLabel(labelText);
            l.setFor(forId);
            add(l);
        } else {
            add(Dom.span("ap-lbl", labelText));
        }
        add(control);
        if (hint != null) {
            Paragraph p = new Paragraph(hint);
            p.addClassName("ap-hint");
            add(p);
        }
    }

    public static Input textInput(String id, String value, int maxLength) {
        Input in = new Input(ValueChangeMode.ON_CHANGE);
        in.setId(id);
        in.setValue(value == null ? "" : value);
        in.getElement().setAttribute("maxlength", String.valueOf(maxLength));
        return in;
    }
}
