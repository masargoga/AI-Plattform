package com.agenticpilot.ui.components;

import com.agenticpilot.common.InvalidInputException;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.UI;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

/** Short confirmation and error messages. */
public final class Feedback {

    private Feedback() {
    }

    public static void saved(Component from, String messageKey) {
        Notification n = Notification.show(from.getTranslation(messageKey), 2500, Notification.Position.BOTTOM_CENTER);
        n.addThemeVariants(NotificationVariant.SUCCESS);
    }

    /** Runs the action and turns known failures into a message; returns true when it succeeded. */
    public static boolean run(Component from, Runnable action) {
        try {
            action.run();
            return true;
        } catch (InvalidInputException e) {
            error(from.getTranslation(e.getMessage(), e.getArgs()));
        } catch (AccessDeniedException e) {
            error(from.getTranslation("error.denied"));
        } catch (OptimisticLockingFailureException e) {
            error(from.getTranslation("error.conflict"));
        }
        return false;
    }

    public static void error(String message) {
        Notification n = Notification.show(message, 5000, Notification.Position.BOTTOM_CENTER);
        n.addThemeVariants(NotificationVariant.ERROR);
    }

    public static void navigate(Class<? extends Component> target) {
        UI.getCurrent().navigate(target);
    }
}
