package com.yumpoo.platform.identityaccess.application.directory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DirectoryOptionalFieldTest {

    @Test
    void appliesPresentClearAndUnavailableSemantics() {
        assertThat(DirectoryOptionalField.present(" new@example.test ")
                .applyTo("old@example.test")).isEqualTo("new@example.test");
        assertThat(DirectoryOptionalField.clear().applyTo("old@example.test")).isNull();
        assertThat(DirectoryOptionalField.unavailable().applyTo("old@example.test"))
                .isEqualTo("old@example.test");
    }

    @Test
    void doesNotExposePersonalDataInStringRepresentation() {
        assertThat(DirectoryOptionalField.present("secret@example.test").toString())
                .doesNotContain("secret@example.test")
                .contains("REDACTED");
    }
}
