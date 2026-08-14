package com.yumpoo.platform.identityaccess.application.directory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DirectoryProfileMapperTest {

    @Test
    void sortsDepartmentsByNumericIdAndKeepsOptionalFieldStates() {
        WeComRawMemberProfile raw = new WeComRawMemberProfile(
                "member-a",
                "Alice",
                DirectoryOptionalField.unavailable(),
                DirectoryOptionalField.clear(),
                List.of(20L, 3L, 20L)
        );

        WeComMemberProfile mapped = DirectoryProfileMapper.map(
                raw,
                Map.of(3L, "研发部", 20L, "华东区")
        );

        assertThat(mapped.departmentSummary()).isEqualTo("研发部、华东区");
        assertThat(mapped.email().state()).isEqualTo(DirectoryOptionalField.State.UNAVAILABLE);
        assertThat(mapped.mobile().state()).isEqualTo(DirectoryOptionalField.State.CLEAR);
        assertThat(mapped.rawProfileHash().value()).hasSize(64);
    }

    @Test
    void failsWhenDepartmentNameIsOutsideReadableScope() {
        WeComRawMemberProfile raw = new WeComRawMemberProfile(
                "member-a",
                "Alice",
                DirectoryOptionalField.clear(),
                DirectoryOptionalField.clear(),
                List.of(3L)
        );

        assertThatThrownBy(() -> DirectoryProfileMapper.map(raw, Map.of()))
                .isInstanceOfSatisfying(DirectorySyncException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo("DIRECTORY_DEPARTMENT_UNAVAILABLE"));
    }

    @Test
    void canonicalHashDistinguishesUnavailableFromClear() {
        WeComRawMemberProfile unavailable = profile(DirectoryOptionalField.unavailable());
        WeComRawMemberProfile clear = profile(DirectoryOptionalField.clear());

        assertThat(DirectoryCanonicalHash.profile(unavailable, "研发部"))
                .isNotEqualTo(DirectoryCanonicalHash.profile(clear, "研发部"));
        assertThat(DirectoryCanonicalHash.profile(unavailable, "研发部"))
                .isEqualTo(DirectoryCanonicalHash.profile(profile(
                        DirectoryOptionalField.unavailable()), "研发部"));
    }

    private static WeComRawMemberProfile profile(DirectoryOptionalField email) {
        return new WeComRawMemberProfile(
                "member-a",
                "Alice",
                email,
                DirectoryOptionalField.present("13800000000"),
                List.of(3L)
        );
    }
}
