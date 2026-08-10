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
    void packageLayoutRuleRejectsUnknownModulesAndLayers() {
        assertThat(ArchitectureRules.packageLayoutViolations(INVALID_FIXTURES, FIXTURE_ROOT))
                .anyMatch(message -> message.contains("未知一级模块 rogue"))
                .anyMatch(message -> message.contains("未知模块层级 workitem.rogue"));
    }

    @Test
    void dependencyRuleRejectsAnUnapprovedInternalModuleDependency() {
        assertThat(ArchitectureRules.moduleBoundaryViolations(INVALID_FIXTURES, FIXTURE_ROOT))
                .anyMatch(message -> message.contains("workitem -> notification"));
    }

    @Test
    void dependencyRuleRejectsRepositoryImplementationFromAnAllowedModule() {
        assertThat(ArchitectureRules.moduleBoundaryViolations(INVALID_FIXTURES, FIXTURE_ROOT))
                .anyMatch(message -> message.contains("跨模块只能依赖目标模块 api")
                        && message.contains("CatalogJdbcRepository"));
    }

    @Test
    void layerRuleRejectsApiDependingDirectlyOnDomain() {
        assertThat(ArchitectureRules.moduleBoundaryViolations(INVALID_FIXTURES, FIXTURE_ROOT))
                .anyMatch(message -> message.contains("非法层级依赖")
                        && message.contains("ApiDomainLeak"));
    }

    @Test
    void layerRuleRejectsInfrastructureDependingOnApi() {
        assertThat(ArchitectureRules.moduleBoundaryViolations(INVALID_FIXTURES, FIXTURE_ROOT))
                .anyMatch(message -> message.contains("非法层级依赖")
                        && message.contains("InfrastructureApiLeak"));
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

    @Test
    void apiRuleRejectsDirectJdbcAccess() {
        assertThatThrownBy(() -> ArchitectureRules.apiDoesNotAccessPersistenceTechnology().check(INVALID_FIXTURES))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void controllerRuleRejectsDefaultAndExplicitRequiredIfMatchHeaders() {
        assertThat(ArchitectureRules.requiredIfMatchHeaderViolations(INVALID_FIXTURES))
                .anyMatch(message -> message.contains("defaultRequiredIfMatch"))
                .anyMatch(message -> message.contains("explicitRequiredIfMatch"))
                .noneMatch(message -> message.contains("optionalIfMatch"));
    }
}
