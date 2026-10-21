package com.agenticpilot.ui;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Viewport;
import com.vaadin.flow.theme.lumo.Lumo;

@StyleSheet(Lumo.STYLESHEET)
@StyleSheet("styles.css")
@Viewport("width=device-width, initial-scale=1, viewport-fit=cover")
public class AppShell implements AppShellConfigurator {
}
