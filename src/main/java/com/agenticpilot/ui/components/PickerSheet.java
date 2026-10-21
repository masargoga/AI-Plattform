package com.agenticpilot.ui.components;

import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.VaadinIcon;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Multi-select list of options in a sheet: used for adding skills and data sources. */
public class PickerSheet extends Sheet {

    public record Item(UUID id, VaadinIcon icon, String title, String meta) {
    }

    private final Set<UUID> selected = new LinkedHashSet<>();
    private final NativeButton confirm;

    public PickerSheet(String title, String subtitle, List<Item> items, String emptyText, String confirmText,
                       Consumer<Set<UUID>> onConfirm) {
        super(title, subtitle);
        Div list = Dom.div("ap-opts");
        if (items.isEmpty()) {
            Paragraph p = new Paragraph(emptyText);
            p.addClassName("ap-rowempty");
            list.add(p);
        }
        for (Item item : items) {
            list.add(option(item));
        }
        setBody(list);
        confirm = Dom.button("ap-btn ap-primary", confirmText, () -> {
            close();
            onConfirm.accept(Set.copyOf(selected));
        });
        confirm.setEnabled(false);
        NativeButton cancel = Dom.button("ap-btn ap-ghost", getTranslation("action.cancel"), this::close);
        setFooter(cancel, confirm);
    }

    private NativeButton option(Item item) {
        NativeButton opt = new NativeButton();
        opt.getElement().setAttribute("type", "button");
        opt.addClassName("ap-opt");
        opt.getElement().setAttribute("role", "checkbox");
        opt.getElement().setAttribute("aria-checked", "false");
        Div tick = Dom.div("ap-tick");
        opt.add(Dom.span("ap-ico", Dom.icon(item.icon(), 18)),
                Dom.span("ap-opt-main", Dom.span(null, item.title()), Dom.span("ap-hint", item.meta())),
                tick);
        opt.getChildren().filter(c -> c.hasClassName("ap-opt-main")).findFirst()
                .ifPresent(m -> m.getChildren().findFirst().ifPresent(t -> t.getElement().getStyle().set("font-weight", "600")));
        opt.addClickListener(e -> {
            boolean on = selected.add(item.id());
            if (!on) {
                selected.remove(item.id());
            }
            opt.setClassName("ap-on", on);
            opt.getElement().setAttribute("aria-checked", String.valueOf(on));
            tick.removeAll();
            if (on) {
                tick.add(Dom.icon(VaadinIcon.CHECK, 14));
            }
            confirm.setEnabled(!selected.isEmpty());
        });
        return opt;
    }
}
