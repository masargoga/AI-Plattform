package com.agenticpilot.ui.components;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Segmented control: one value selected at a time (the click-dummy's .seg). */
public class Segmented<T> extends Div {

    public record Option<T>(T value, String label) {
    }

    private final Map<T, NativeButton> buttons = new LinkedHashMap<>();
    private final List<Consumer<T>> listeners = new ArrayList<>();
    private T value;

    public Segmented(List<Option<T>> options, T initial) {
        addClassName("ap-seg");
        getElement().setAttribute("role", "group");
        for (Option<T> o : options) {
            NativeButton b = Dom.button(null, o.label(), () -> select(o.value(), true));
            buttons.put(o.value(), b);
            add(b);
        }
        select(initial, false);
    }

    public T getValue() {
        return value;
    }

    public void setValue(T v) {
        select(v, false);
    }

    public Segmented<T> onChange(Consumer<T> listener) {
        listeners.add(listener);
        return this;
    }

    public void setEnabled(boolean enabled) {
        buttons.values().forEach(b -> b.setEnabled(enabled));
    }

    private void select(T v, boolean fire) {
        boolean changed = !java.util.Objects.equals(value, v);
        value = v;
        buttons.forEach((key, b) -> {
            boolean on = java.util.Objects.equals(key, v);
            b.setClassName("ap-on", on);
            b.getElement().setAttribute("aria-pressed", String.valueOf(on));
        });
        if (fire && changed) {
            listeners.forEach(l -> l.accept(v));
        }
    }
}
