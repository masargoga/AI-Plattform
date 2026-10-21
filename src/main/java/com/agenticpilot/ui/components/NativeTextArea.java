package com.agenticpilot.ui.components;

import com.vaadin.flow.component.AbstractSinglePropertyField;
import com.vaadin.flow.component.Tag;

/** A plain textarea whose value is synced on change. */
@Tag("textarea")
public class NativeTextArea extends AbstractSinglePropertyField<NativeTextArea, String> {

    public NativeTextArea(String id, String value, int rows, int maxLength) {
        super("value", "", false);
        setId(id);
        getElement().setAttribute("rows", String.valueOf(rows));
        getElement().setAttribute("maxlength", String.valueOf(maxLength));
        getElement().setProperty("value", value == null ? "" : value);
        getElement().setAttribute("spellcheck", "true");
        setSynchronizedEvent("change");
        setValue(value == null ? "" : value);
    }

    public NativeTextArea asMono() {
        addClassName("ap-mono");
        return this;
    }
}
