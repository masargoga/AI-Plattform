package com.agenticpilot.ui.components;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Input;
import com.vaadin.flow.component.html.NativeLabel;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.data.value.ValueChangeMode;
import java.util.function.Consumer;

public class SearchBox extends Div {

    private final Input input = new Input(ValueChangeMode.LAZY);

    public SearchBox(String id, String label, String placeholder, Consumer<String> onChange) {
        addClassName("ap-search");
        NativeLabel l = new NativeLabel(label);
        l.setFor(id);
        l.addClassName("sr");
        l.getStyle().set("position", "absolute").set("width", "1px").set("height", "1px").set("overflow", "hidden");
        input.setId(id);
        input.setType("search");
        input.setPlaceholder(placeholder);
        input.setValueChangeTimeout(250);
        input.addValueChangeListener(e -> onChange.accept(e.getValue() == null ? "" : e.getValue()));
        add(Dom.icon(VaadinIcon.SEARCH, 18), l, input);
    }
}
