package com.segurplus.shared;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI con esquema de seguridad Bearer JWT aplicado por defecto; los endpoints de sesión lo anulan con {@code @SecurityRequirements}. */
@Configuration
class OpenApiConfig {

    static final String BEARER = "bearerAuth";

    @Bean
    OpenAPI segurPlusOpenApi() {
        return new OpenAPI()
                .info(new Info().title("SegurPlus API").version("v1")
                        .description("Backend de SegurPlus. Identidad solo desde el JWT; recurso no autorizado => 404."))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
