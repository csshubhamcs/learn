package com.learn.userservice.auth.config;

import com.learn.userservice.auth.exception.ApiError;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc/OpenAPI wiring: declares the bearer-JWT security scheme, and adds 401/403/500 to
 * every operation once via an {@link OpenApiCustomizer} instead of repeating them per method.
 * Codes specific to one endpoint (409, 410, 503) are declared on that controller method instead.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI userServiceOpenApi() {
        final String scheme = "bearerAuth";
        return new OpenAPI()
                .info(new Info().title("user-service API").version("v1"))
                .addSecurityItem(new SecurityRequirement().addList(scheme))
                .components(new Components()
                        .addSecuritySchemes(
                                scheme,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
    }

    @Bean
    OpenApiCustomizer commonErrorResponsesCustomizer() {
        return openApi -> {
            registerApiErrorSchema(openApi);
            Content errorBody = new Content()
                    .addMediaType(
                            "application/json",
                            new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiError")));

            openApi.getPaths()
                    .values()
                    .forEach(pathItem -> pathItem.readOperations().forEach(operation -> {
                        ApiResponses responses = operation.getResponses();
                        addIfAbsent(
                                responses,
                                "401",
                                "Unauthenticated - the request has no valid bearer token.",
                                errorBody);
                        addIfAbsent(
                                responses,
                                "403",
                                "Forbidden - authenticated, but the token's role does not permit this operation.",
                                errorBody);
                        addIfAbsent(responses, "500", "Unexpected server error.", errorBody);
                        guaranteeSuccessResponse(responses);
                    }));
        };
    }

    /**
     * Springdoc normally infers the success response from the handler's return type, but it
     * silently omits it on some operations that also declare an explicit {@code @ApiResponse}.
     * A spec that documents only failures is not usable by a client generator, so guarantee a
     * 2xx here rather than depending on inference holding for every method.
     */
    private void guaranteeSuccessResponse(ApiResponses responses) {
        boolean hasSuccess = responses.keySet().stream().anyMatch(code -> code.startsWith("2"));
        if (!hasSuccess) {
            responses.addApiResponse("200", new ApiResponse().description("Success."));
        }
    }

    private void addIfAbsent(ApiResponses responses, String code, String description, Content body) {
        if (!responses.containsKey(code)) {
            responses.addApiResponse(
                    code, new ApiResponse().description(description).content(body));
        }
    }

    private void registerApiErrorSchema(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        if (openApi.getComponents().getSchemas() != null
                && openApi.getComponents().getSchemas().containsKey("ApiError")) {
            return;
        }
        ResolvedSchema resolved =
                ModelConverters.getInstance().resolveAsResolvedSchema(new AnnotatedType(ApiError.class));
        resolved.referencedSchemas.forEach(openApi.getComponents()::addSchemas);
        openApi.getComponents().addSchemas("ApiError", resolved.schema);
    }
}
