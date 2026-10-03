package com.yumpoo.platform.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleArchitectureTest {

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(ArchitectureRules.PRODUCTION_ROOT);

    @Test
    void productionPackagesUseOnlyKnownModulesAndLayers() {
        assertThat(ArchitectureRules.packageLayoutViolations(
                PRODUCTION_CLASSES,
                ArchitectureRules.PRODUCTION_ROOT
        )).isEmpty();
    }

    @Test
    void everyModuleContainsAllFourLayerMarkers() {
        assertThat(ArchitectureRules.missingModuleLayerMarkers(
                PRODUCTION_CLASSES,
                ArchitectureRules.PRODUCTION_ROOT
        )).isEmpty();
    }

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

    @Test
    void apiControllersDeferRequiredIfMatchChecksUntilAfterVisibleLookup() {
        assertThat(ArchitectureRules.requiredIfMatchHeaderViolations(PRODUCTION_CLASSES)).isEmpty();
    }

    @Test
    void workitemDoesNotReadCatalogProjectOrMembershipTables() throws IOException {
        var forbidden = Pattern.compile("(?i)yumpoo\\.project\\b|project_membership");
        try (var files = Files.walk(Path.of("src/main/java/com/yumpoo/platform/workitem"))) {
            for (var file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                assertThat(forbidden.matcher(Files.readString(file)).find()).as("catalog SQL in %s", file).isFalse();
            }
        }
    }
}
