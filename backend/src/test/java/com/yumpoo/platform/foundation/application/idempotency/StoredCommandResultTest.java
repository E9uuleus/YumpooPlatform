package com.yumpoo.platform.foundation.application.idempotency;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoredCommandResultTest {

    @Test
    void acceptsNullAndStrongDecimalEtagsWithinTheNonNegativeLongRange() {
        String maximumLengthLeadingZeroEtag = '"' + "0".repeat(126) + '"';

        assertThat(result(null).etag()).isNull();
        assertThat(result("\"0\"").etag()).isEqualTo("\"0\"");
        assertThat(result("\"0009\"").etag()).isEqualTo("\"0009\"");
        assertThat(result('"' + Long.toString(Long.MAX_VALUE) + '"').etag())
                .isEqualTo("\"9223372036854775807\"");
        assertThat(result(maximumLengthLeadingZeroEtag).etag()).hasSize(128);
    }

    @ParameterizedTest
    @MethodSource("unsafeEtags")
    void rejectsEtagsThatAreNotBoundedStrongDecimalValidators(String etag) {
        assertThatThrownBy(() -> result(etag))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("etag must be null or a strong decimal ETag containing a non-negative long");
    }

    private static Stream<String> unsafeEtags() {
        return Stream.of(
                "",
                " ",
                "0",
                "\"\"",
                "\"1a\"",
                "W/\"1\"",
                "*",
                "\"1\", \"2\"",
                "\"9223372036854775808\"",
                '"' + "9".repeat(127) + '"'
        );
    }

    private static StoredCommandResult result(String etag) {
        return new StoredCommandResult(200, "{}", UUID.randomUUID(), etag);
    }
}
