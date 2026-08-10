package com.yumpoo.platform.foundation.application.concurrency;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConditionalUpdateGuardTest {

    @Test
    void acceptsOneUpdatedRowWithoutClassifyingAFailure() {
        AtomicBoolean invoked = new AtomicBoolean();

        ConditionalUpdateGuard.requireSingleRowUpdated(1, () -> {
            invoked.set(true);
            return ConditionalUpdateFailure.VERSION_CONFLICT;
        });

        assertThat(invoked).isFalse();
    }

    @ParameterizedTest
    @MethodSource("failureMappings")
    void mapsZeroUpdatedRowsToAStableApplicationError(
            ConditionalUpdateFailure failure,
            StandardErrorCode expectedErrorCode
    ) {
        assertThatThrownBy(() -> ConditionalUpdateGuard.requireSingleRowUpdated(0, () -> failure))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(expectedErrorCode));
    }

    @ParameterizedTest
    @MethodSource("illegalRowCounts")
    void rejectsRowCountsOutsideZeroAndOneWithoutClassifyingAFailure(int updatedRows) {
        AtomicBoolean invoked = new AtomicBoolean();

        assertThatThrownBy(() -> ConditionalUpdateGuard.requireSingleRowUpdated(updatedRows, () -> {
            invoked.set(true);
            return ConditionalUpdateFailure.INVALID_STATE;
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must affect zero or one row");
        assertThat(invoked).isFalse();
    }

    @Test
    void rejectsANullFailureResult() {
        assertThatThrownBy(() -> ConditionalUpdateGuard.requireSingleRowUpdated(0, () -> null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("failureSupplier must return a failure");
    }

    private static Stream<Arguments> failureMappings() {
        return Stream.of(
                Arguments.of(
                        ConditionalUpdateFailure.RESOURCE_NOT_VISIBLE,
                        StandardErrorCode.RESOURCE_NOT_FOUND
                ),
                Arguments.of(
                        ConditionalUpdateFailure.VERSION_CONFLICT,
                        StandardErrorCode.VERSION_CONFLICT
                ),
                Arguments.of(
                        ConditionalUpdateFailure.INVALID_STATE,
                        StandardErrorCode.INVALID_STATE_TRANSITION
                )
        );
    }

    private static Stream<Integer> illegalRowCounts() {
        return Stream.of(-1, 2, Integer.MAX_VALUE);
    }
}
