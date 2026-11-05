package com.yumpoo.platform.testing;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.core.util.Yaml;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenApiNullableReferencesTest {

    private static OpenApiInteractionValidator validator;

    @BeforeAll
    static void createValidator() throws IOException {
        ObjectNode spec = (ObjectNode) Yaml.mapper().readTree("""
                openapi: 3.0.3
                info: {title: Nullable reference test, version: "1.0"}
                servers: [{url: /api/v1}]
                paths:
                  /example:
                    get:
                      responses:
                        "200":
                          description: Test payload
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [child, choice]
                                properties:
                                  child:
                                    type: object
                                    nullable: true
                                    allOf: [{$ref: "#/components/schemas/Child"}]
                                  choice:
                                    $ref: "#/components/schemas/NullableChoice"
                components:
                  schemas:
                    Child:
                      type: object
                      additionalProperties: false
                      required: [name]
                      properties:
                        name: {type: string}
                    NullableChoice:
                      type: string
                      nullable: true
                      allOf: [{$ref: "#/components/schemas/Choice"}]
                    Choice:
                      type: string
                      enum: [OPEN, CLOSED]
                """);
        OpenApiNullableReferences.normalize(spec);
        validator = OpenApiInteractionValidator.createForInlineApiSpecification(Yaml.mapper().writeValueAsString(spec))
                .withBasePathOverride("/api/v1")
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"child\":null,\"choice\":null}",
            "{\"child\":{\"name\":\"item\"},\"choice\":\"OPEN\"}"
    })
    void acceptsNullAndValidReferencedValues(String body) {
        ValidationReport report = validate(body);
        assertThat(report.hasErrors()).withFailMessage(() -> report.getMessages().toString()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"child\":{},\"choice\":\"OPEN\"}",
            "{\"child\":\"item\",\"choice\":\"OPEN\"}",
            "{\"child\":{\"name\":\"item\",\"extra\":true},\"choice\":\"OPEN\"}",
            "{\"child\":null,\"choice\":\"UNKNOWN\"}",
            "{\"child\":null,\"choice\":1}"
    })
    void rejectsValuesThatViolateReferencedSchemas(String body) {
        assertThat(validate(body).getMessages()).anyMatch(message ->
                message.getLevel() == ValidationReport.Level.ERROR
                        && message.getKey().startsWith("validation.response.body.schema."));
    }

    @Test
    void reportsAllUnsupportedShapesWithPointersAndExtraKeys() throws IOException {
        ObjectNode spec = (ObjectNode) Yaml.mapper().readTree("""
                paths:
                  /test~item:
                    nullable: true
                    type: string
                    allOf: [{$ref: "#/a"}, {$ref: "#/b"}]
                components:
                  schemas:
                    Extra:
                      nullable: true
                      type: string
                      allOf: [{$ref: "#/a"}]
                      enum: [a]
                    BadMember:
                      nullable: true
                      allOf: [{$ref: "#/a", minLength: 1}]
                """);
        assertThatThrownBy(() -> OpenApiNullableReferences.normalize(spec))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/paths/~1test~0item/allOf")
                .hasMessageContaining("/components/schemas/Extra：多余键 [enum]")
                .hasMessageContaining("/components/schemas/BadMember：缺少 type")
                .hasMessageContaining("/components/schemas/BadMember/allOf/0：多余键 [minLength]");
    }

    private static ValidationReport validate(String body) {
        SimpleResponse response = SimpleResponse.Builder.ok().withContentType("application/json").withBody(body).build();
        return validator.validateResponse("/api/v1/example", Request.Method.GET, response);
    }
}
