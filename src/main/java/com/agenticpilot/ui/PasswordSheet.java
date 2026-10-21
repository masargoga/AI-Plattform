package com.agenticpilot.ui;

import com.agenticpilot.ui.components.Dom;
import com.agenticpilot.ui.components.Feedback;
import com.agenticpilot.ui.components.FormField;
import com.agenticpilot.ui.components.Sheet;
import com.agenticpilot.user.AccountService;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Input;

/** Change the signed-in user's own password. */
class PasswordSheet extends Sheet {

    PasswordSheet(AccountService account) {
        super(UI.getCurrent().getTranslation("account.password.title"), UI.getCurrent().getTranslation("account.password.subtitle"));
        Input current = field("pw-current", "current-password");
        Input next = field("pw-new", "new-password");
        Input repeat = field("pw-repeat", "new-password");
        setBody(
                new FormField(getTranslation("account.password.current"), "pw-current", current, null),
                new FormField(getTranslation("account.password.new"), "pw-new", next,
                        getTranslation("account.password.hint", AccountService.MIN_PASSWORD_LENGTH)),
                new FormField(getTranslation("account.password.confirm"), "pw-repeat", repeat, null));
        setFooter(
                Dom.button("ap-btn ap-ghost", getTranslation("action.cancel"), this::close),
                Dom.button("ap-btn ap-primary", getTranslation("account.password.save"), () -> {
                    if (Feedback.run(this, () -> account.changePassword(current.getValue(), next.getValue(), repeat.getValue()))) {
                        Feedback.saved(this, "account.password.saved");
                        close();
                    }
                }));
    }

    private static Input field(String id, String autocomplete) {
        Input in = FormField.textInput(id, "", 128);
        in.setType("password");
        in.getElement().setAttribute("autocomplete", autocomplete);
        return in;
    }
}
