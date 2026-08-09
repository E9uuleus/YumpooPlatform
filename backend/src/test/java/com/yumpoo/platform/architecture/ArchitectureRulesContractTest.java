package com.yumpoo.platform.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchitectureRulesContractTest {

    private static final String FIXTURE_ROOT = "com.yumpoo.archfixture";
    private static final JavaClasses INVALID_FIXTURES = new ClassFileImporter()
            .importPackages(FIXTURE_ROOT);

    @Test
    void dependencyRuleRejectsAnUnapprovedInternalModuleDependency() {
        assertThat(ArchitectureRules.moduleBoundaryViolations(INVALID_FIXTURES, FIXTURE_ROOT))
                .anyMatch(message -> message.contains("workitem -> notification"));
    }

    @Test
    void cycleRuleRejectsAModuleCycle() {
        assertThatThrownBy(() -> ArchitectureRules.modulesAreAcyclic(FIXTURE_ROOT).check(INVALID_FIXTURES))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void domainRuleRejectsSpringCoupling() {
        assertThatThrownBy(() -> ArchitectureRules.domainsAreFrameworkIndependent().check(INVALID_FIXTURES))
                .isInstanceOf(AssertionError.class);
    }
}
