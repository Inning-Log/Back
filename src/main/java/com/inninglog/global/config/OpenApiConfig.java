package com.inninglog.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springdoc.core.customizers.OpenApiCustomizer;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_AUTH = "bearerAuth";

    @Bean
    public OpenApiCustomizer nullableSchemas() {
        return api -> {
            if (api.getComponents() == null || api.getComponents().getSchemas() == null) return;
            Set<Schema> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            api.getComponents().getSchemas().values().forEach(schema -> normalizeNullableSchemas(schema, visited));
        };
    }

    // OpenAPI 3.1 interprets $ref siblings as AND. A nullable reference must use OR,
    // otherwise swagger-core's {$ref: ..., type: "null"} accepts neither objects nor null.
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void normalizeNullableSchemas(Schema schema, Set<Schema> visited) {
        if (schema == null || !visited.add(schema)) return;
        boolean nullable = Boolean.TRUE.equals(schema.getNullable())
                || "null".equals(schema.getType())
                || (schema.getTypes() != null && schema.getTypes().contains("null"));
        // A union type alone cannot admit null while an enum still excludes it.
        if (nullable && schema.getEnum() != null && !schema.getEnum().contains(null)) {
            List<Object> values = new ArrayList<>(schema.getEnum());
            values.add(null);
            schema.setEnum(values);
        }
        if (schema.get$ref() != null && nullable) {
            Schema reference = new Schema().$ref(schema.get$ref());
            Schema nullValue = new Schema();
            nullValue.setTypes(Set.of("null"));
            schema.set$ref(null);
            schema.setType(null);
            schema.setTypes(null);
            schema.setNullable(null);
            schema.setAnyOf(List.of(reference, nullValue));
        }
        if (schema.getProperties() != null) {
            schema.getProperties().values().forEach(child -> normalizeNullableSchemas((Schema) child, visited));
        }
        normalizeNullableSchemas(schema.getItems(), visited);
        if (schema.getAdditionalProperties() instanceof Schema additional) normalizeNullableSchemas(additional, visited);
        for (List<Schema> children : List.of(
                schema.getAllOf() == null ? List.<Schema>of() : schema.getAllOf(),
                schema.getAnyOf() == null ? List.<Schema>of() : schema.getAnyOf(),
                schema.getOneOf() == null ? List.<Schema>of() : schema.getOneOf())) {
            children.forEach(child -> normalizeNullableSchemas(child, visited));
        }
    }

    @Bean
    public OpenAPI openAPI() {
        SecurityScheme bearerScheme = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT");

        return new OpenAPI()
                .info(new Info()
                        .title("Inning Log API")
                        .description("이닝로그 백엔드 API 명세. 별도 표기가 없으면 Bearer JWT 인증이 필요합니다.")
                        .version("v1"))
                .components(new Components().addSecuritySchemes(BEARER_AUTH, bearerScheme))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }
}
