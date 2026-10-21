package com.agenticpilot.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI agenticPilotApi() {
        return new OpenAPI()
                .info(new Info().title("AgenticPilot API").version("1.0")
                        .description("Run managed agents and check text against guardrails. Keys belong to one agent, are shown once and can be revoked. "
                                + "Errors always use the same JSON format. Calls are rate limited per key (X-RateLimit-* headers, 429 with Retry-After)."))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").description("`ap_...` key created on the agent page")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
