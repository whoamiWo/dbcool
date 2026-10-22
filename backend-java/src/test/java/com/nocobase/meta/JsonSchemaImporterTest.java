package com.nocobase.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-007: JsonSchemaImporter 单元测试。
 */
class JsonSchemaImporterTest {

    private JsonSchemaImporter importer;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        importer = new JsonSchemaImporter(om);
    }

    @Test
    void mapsSimpleStringFields() {
        String schema = """
            {
              "type": "object",
              "properties": {
                "name": {"type": "string"},
                "age": {"type": "number"}
              }
            }
            """;
        List<FieldDef> fields = importer.importFields(schema);
        assertThat(fields).hasSize(2);
        assertThat(fields.get(0).name()).isEqualTo("name");
        assertThat(fields.get(0).type()).isEqualTo("text");
        assertThat(fields.get(1).name()).isEqualTo("age");
        assertThat(fields.get(1).type()).isEqualTo("number");
    }

    @Test
    void marksRequiredFields() {
        String schema = """
            {
              "type": "object",
              "properties": {
                "email": {"type": "string", "format": "email"},
                "phone": {"type": "string"}
              },
              "required": ["email"]
            }
            """;
        List<FieldDef> fields = importer.importFields(schema);
        assertThat(fields).hasSize(2);
        assertThat(fields.get(0).name()).isEqualTo("email");
        assertThat(fields.get(0).required()).isTrue();
        assertThat(fields.get(0).type()).isEqualTo("email");
        assertThat(fields.get(1).name()).isEqualTo("phone");
        // 无 format 的 string 映射为 text
        assertThat(fields.get(1).type()).isEqualTo("text");
    }

    @Test
    void mapsEnumToSelect() {
        String schema = """
            {
              "type": "object",
              "properties": {
                "status": {
                  "type": "string",
                  "enum": ["active", "inactive"],
                  "title": "状态"
                }
              }
            }
            """;
        List<FieldDef> fields = importer.importFields(schema);
        assertThat(fields).hasSize(1);
        assertThat(fields.get(0).type()).isEqualTo("select");
        assertThat(fields.get(0).label()).isEqualTo("状态");
        Object rawOptions = fields.get(0).options().get("options");
        assertThat(rawOptions).isInstanceOf(com.fasterxml.jackson.databind.JsonNode.class);
        com.fasterxml.jackson.databind.JsonNode options = (com.fasterxml.jackson.databind.JsonNode) rawOptions;
        assertThat(options.size()).isEqualTo(2);
        assertThat(options.get(0).asText()).isEqualTo("active");
        assertThat(options.get(1).asText()).isEqualTo("inactive");
    }

    @Test
    void mapsTitleToLabel() {
        String schema = """
            {
              "type": "object",
              "properties": {
                "city": {"type": "string", "title": "城市"}
              }
            }
            """;
        List<FieldDef> fields = importer.importFields(schema);
        assertThat(fields).hasSize(1);
        assertThat(fields.get(0).label()).isEqualTo("城市");
    }

    @Test
    void mapsDefaultToDefaultValue() {
        String schema = """
            {
              "type": "object",
              "properties": {
                "count": {"type": "number", "default": 0}
              }
            }
            """;
        List<FieldDef> fields = importer.importFields(schema);
        assertThat(fields).hasSize(1);
        assertThat(fields.get(0).defaultValue()).isEqualTo("0");
    }

    @Test
    void duplicateKeysMergeInJson() {
        String schema = """
            {
              "type": "object",
              "properties": {
                "name": {"type": "string"},
                "name": {"type": "number"}
              }
            }
            """;
        // JSON 解析后只有一个 name，不会触发重复检测。
        List<FieldDef> fields = importer.importFields(schema);
        assertThat(fields).hasSize(1);
    }

    @Test
    void unsupportedTypeThrows400() {
        String schema = """
            {
              "type": "object",
              "properties": {
                "meta": {"type": "object"}
              }
            }
            """;
        assertThatThrownBy(() -> importer.importFields(schema))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void missingPropertiesThrows400() {
        String schema = """
            {
              "type": "string"
            }
            """;
        assertThatThrownBy(() -> importer.importFields(schema))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void nonObjectSchemaThrows400() {
        String schema = "[\"a\", \"b\"]";
        assertThatThrownBy(() -> importer.importFields(schema))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void emptyPropertiesReturnsEmptyList() {
        String schema = """
            {
              "type": "object",
              "properties": {}
            }
            """;
        List<FieldDef> fields = importer.importFields(schema);
        assertThat(fields).isEmpty();
    }
}