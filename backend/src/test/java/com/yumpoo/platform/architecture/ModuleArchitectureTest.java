package com.yumpoo.platform.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleArchitectureTest {

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(ArchitectureRules.PRODUCTION_ROOT);

    @Test
    void modulesFollowTheAllowedDependencyMatrixAndLayerBoundaries() {
        assertThat(ArchitectureRules.moduleBoundaryViolations(
                PRODUCTION_CLASSES,
                ArchitectureRules.PRODUCTION_ROOT
        )).isEmpty();
    }

    @Test
    void modulesAreFreeOfCycles() {
        ArchitectureRules.modulesAreAcyclic(ArchitectureRules.PRODUCTION_ROOT).check(PRODUCTION_CLASSES);
    }

    @Test
    void domainsDoNotDependOnFrameworks() {
        ArchitectureRules.domainsAreFrameworkIndependent().check(PRODUCTION_CLASSES);
    }

    @Test
    void apiDoesNotAccessPersistenceTechnology() {
        ArchitectureRules.apiDoesNotAccessPersistenceTechnology().check(PRODUCTION_CLASSES);
    }
}
