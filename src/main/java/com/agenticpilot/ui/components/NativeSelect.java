package com.agenticpilot.ui.components;

import com.vaadin.flow.component.AbstractSinglePropertyField;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.dom.Element;
import java.util.List;

/** A plain select element. Options are (value, label) pairs; the value is a string key. */
@Tag("select")
public class NativeSelect extends AbstractSinglePropertyField<NativeSelect, String> {

    public record Choice(String value, String label) {
    }

    public NativeSelect(String id, List<Choice> choices, String value) {
        super("value", value, false);
        setId(id);
        for (Choice c : choices) {
            Element o = new Element("option").setText(c.label());
            o.setAttribute("value", c.value());
            if (c.value().equals(value)) {
                o.setAttribute("selected", true);
            }
            getElement().appendChild(o);
        }
        setSynchronizedEvent("change");
        setValue(value);
    }
}
