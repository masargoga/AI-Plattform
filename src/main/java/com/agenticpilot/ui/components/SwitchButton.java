package com.agenticpilot.ui.components;

import com.vaadin.flow.component.html.NativeButton;
import java.util.function.Consumer;

/** An on/off switch with role="switch". */
public class SwitchButton extends NativeButton {

    private boolean checked;

    public SwitchButton(boolean checked, String ariaLabel, Consumer<Boolean> onToggle) {
        addClassName("ap-switch");
        getElement().setAttribute("type", "button");
        getElement().setAttribute("role", "switch");
        getElement().setAttribute("aria-label", ariaLabel);
        setChecked(checked);
        if (onToggle != null) {
            addClickListener(e -> {
                setChecked(!this.checked);
                onToggle.accept(this.checked);
            });
        }
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean checked) {
        this.checked = checked;
        getElement().setAttribute("aria-checked", String.valueOf(checked));
    }
}
