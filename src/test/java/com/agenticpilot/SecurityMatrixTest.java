package com.agenticpilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Nothing is reachable without the right identity: every protected service and every API route says no. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityMatrixTest {

    @Autowired
    ApplicationContext context;
    @Autowired
    MockMvc mvc;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private List<Object[]> protectedMethods() {
        List<Object[]> found = new ArrayList<>();
        for (Map.Entry<String, Object> bean : context.getBeansWithAnnotation(PreAuthorize.class).entrySet()) {
            Class<?> target = AopUtils.getTargetClass(bean.getValue());
            for (Method m : target.getDeclaredMethods()) {
                if (Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers()) && !m.isSynthetic()) {
                    found.add(new Object[] {bean.getValue(), m});
                }
            }
        }
        return found;
    }

    private static Object[] defaults(Method m) {
        Object[] args = new Object[m.getParameterCount()];
        Class<?>[] types = m.getParameterTypes();
        for (int i = 0; i < args.length; i++) {
            if (types[i] == boolean.class) {
                args[i] = false;
            } else if (types[i] == int.class) {
                args[i] = 0;
            } else if (types[i] == long.class) {
                args[i] = 0L;
            }
        }
        return args;
    }

    private void assertAllDenied(String who) throws Exception {
        List<Object[]> methods = protectedMethods();
        assertThat(methods).as("protected service methods found").hasSizeGreaterThan(50);
        List<String> open = new ArrayList<>();
        for (Object[] entry : methods) {
            Method m = (Method) entry[1];
            Method onProxy = entry[0].getClass().getMethod(m.getName(), m.getParameterTypes());
            try {
                onProxy.invoke(entry[0], defaults(m));
                open.add(m.getDeclaringClass().getSimpleName() + "." + m.getName());
            } catch (InvocationTargetException e) {
                if (!(e.getCause() instanceof AccessDeniedException) && !(e.getCause() instanceof AuthenticationCredentialsNotFoundException)) {
                    open.add(m.getDeclaringClass().getSimpleName() + "." + m.getName() + " -> " + e.getCause());
                }
            }
        }
        assertThat(open).as("methods that did not refuse " + who).isEmpty();
    }

    @Test
    void anonymousCannotCallAnyService() throws Exception {
        SecurityContextHolder.clearContext();
        assertAllDenied("an anonymous caller");
    }

    @Test
    void anApiKeyCannotCallAnyUiService() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "api-key", "n/a", List.of(new SimpleGrantedAuthority("ROLE_API"))));
        assertAllDenied("an API key");
    }

    @Test
    void everyApiRouteNeedsAKey() throws Exception {
        for (String path : List.of("/api/v1/agents", "/api/v1/approvals/00000000-0000-0000-0000-000000000000")) {
            assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus()).as(path).isEqualTo(401);
        }
        for (String path : List.of("/api/v1/guardrails/check", "/api/v1/agents/00000000-0000-0000-0000-000000000000/messages")) {
            assertThat(mvc.perform(post(path).contentType("application/json").content("{}")).andReturn().getResponse().getStatus()).as(path).isEqualTo(401);
        }
        assertThat(mvc.perform(get("/api/v1/agents").header("Authorization", "Bearer ap_notakey_x")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(delete("/api/v1/agents")).andReturn().getResponse().getStatus()).isIn(401, 405);
    }

    @Test
    void uiRoutesRedirectAnonymousUsersToTheLogin() throws Exception {
        for (String path : List.of("/", "/agents", "/skills", "/sources", "/policies", "/agents/00000000-0000-0000-0000-000000000000")) {
            assertThat(mvc.perform(get(path)).andReturn().getResponse().getRedirectedUrl()).as(path).endsWith("/login");
        }
    }
}
