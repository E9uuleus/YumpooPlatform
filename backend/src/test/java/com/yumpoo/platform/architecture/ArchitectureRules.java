package com.yumpoo.platform.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.lang.ArchRule;
import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

final class ArchitectureRules {

    static final String PRODUCTION_ROOT = "com.yumpoo.platform";

    static final Set<String> MODULES = Set.of(
            "foundation",
            "organization",
            "identityaccess",
            "catalog",
            "templateworkflow",
            "filestorage",
            "workitem",
            "productfeedback",
            "worklog",
            "notification",
            "audit",
            "reporting",
            "administration"
    );

    static final Set<String> LAYERS = Set.of(
            "api", "application", "domain", "infrastructure"
    );

    private static final Map<String, Set<String>> ALLOWED_SAME_MODULE_LAYER_DEPENDENCIES = Map.of(
            "api", Set.of("api", "application"),
            "application", Set.of("application", "domain"),
            "domain", Set.of("domain"),
            "infrastructure", Set.of("infrastructure", "application", "domain")
    );

    private static final Map<String, Set<String>> ALLOWED_MODULE_DEPENDENCIES = Map.ofEntries(
            Map.entry("foundation", Set.of()),
            Map.entry("organization", Set.of("foundation")),
            Map.entry("identityaccess", Set.of("foundation", "organization", "audit")),
            Map.entry("catalog", Set.of("foundation", "identityaccess")),
            Map.entry("templateworkflow", Set.of("foundation")),
            Map.entry("filestorage", Set.of("foundation")),
            Map.entry("workitem", Set.of(
                    "foundation", "catalog", "templateworkflow", "identityaccess", "filestorage", "audit"
            )),
            Map.entry("productfeedback", Set.of(
                    "foundation", "catalog", "workitem", "identityaccess", "filestorage"
            )),
            Map.entry("worklog", Set.of(
                    "foundation", "organization", "catalog", "workitem", "identityaccess"
            )),
            Map.entry("notification", Set.of("foundation", "identityaccess")),
            Map.entry("audit", Set.of("foundation")),
            Map.entry("reporting", Set.of(
                    "foundation", "organization", "identityaccess", "catalog", "templateworkflow",
                    "filestorage", "workitem", "productfeedback", "worklog", "notification", "audit"
            )),
            Map.entry("administration", Set.of(
                    "foundation", "organization", "identityaccess", "catalog", "templateworkflow",
                    "filestorage", "workitem", "productfeedback", "worklog", "notification", "audit",
                    "reporting"
            ))
    );

    private ArchitectureRules() {
    }

    static List<String> packageLayoutViolations(JavaClasses classes, String rootPackage) {
        List<String> violations = new ArrayList<>();
        String rootApplicationClass = rootPackage + ".YumpooServerApplication";
        String packagePrefix = rootPackage + ".";

        for (JavaClass javaClass : classes) {
            String packageName = javaClass.getPackageName();
            if (packageName.equals(rootPackage)) {
                if (!javaClass.getName().equals(rootApplicationClass)) {
                    violations.add("根包只允许启动类 " + rootApplicationClass + ": " + javaClass.getName());
                }
                continue;
            }
            if (!packageName.startsWith(packagePrefix)) {
                continue;
            }

            String[] segments = packageName.substring(packagePrefix.length()).split("\\.");
            if (segments.length == 0 || !MODULES.contains(segments[0])) {
                String actualModule = segments.length == 0 ? "<missing>" : segments[0];
                violations.add("未知一级模块 " + actualModule + ": " + javaClass.getName());
                continue;
            }
            if (segments.length < 2 || !LAYERS.contains(segments[1])) {
                String actualLayer = segments.length < 2 ? "<missing>" : segments[1];
                violations.add("未知模块层级 " + segments[0] + "." + actualLayer + ": " + javaClass.getName());
            }
        }
        return List.copyOf(violations);
    }

    static List<String> missingModuleLayerMarkers(JavaClasses classes, String rootPackage) {
        Set<String> importedClassNames = new HashSet<>();
        for (JavaClass javaClass : classes) {
            importedClassNames.add(javaClass.getName());
        }

        List<String> missingMarkers = new ArrayList<>();
        for (String module : MODULES) {
            for (String layer : LAYERS) {
                String markerClass = rootPackage + "." + module + "." + layer + ".LayerMarker";
                if (!importedClassNames.contains(markerClass)) {
                    missingMarkers.add(markerClass);
                }
            }
        }
        return List.copyOf(missingMarkers);
    }

    static ArchRule modulesAreAcyclic(String rootPackage) {
        return slices()
                .matching(rootPackage + ".(*)..")
                .should().beFreeOfCycles()
                .as("一级业务模块之间不得形成循环依赖");
    }

    static ArchRule domainsAreFrameworkIndependent() {
        return noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "java.sql..",
                        "javax.sql..",
                        "jakarta.servlet..",
                        "javax.servlet.."
                )
                .because("domain 层不得依赖 Spring、JDBC、HTTP 或 Servlet 技术");
    }

    static ArchRule apiDoesNotAccessPersistenceTechnology() {
        return noClasses()
                .that().resideInAPackage("..api..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "java.sql..",
                        "javax.sql..",
                        "org.springframework.jdbc.."
                )
                .because("Controller 和 API 适配层不得直接访问 SQL 或 JDBC");
    }

    static List<String> requiredIfMatchHeaderViolations(JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            if (!javaClass.isAnnotatedWith(ApiV1Controller.class)) {
                continue;
            }
            for (JavaMethod method : javaClass.getMethods()) {
                for (JavaParameter parameter : method.getParameters()) {
                    if (!parameter.isAnnotatedWith(RequestHeader.class)) {
                        continue;
                    }
                    RequestHeader requestHeader = parameter.getAnnotationOfType(RequestHeader.class);
                    String headerName = requestHeader.name().isBlank()
                            ? requestHeader.value()
                            : requestHeader.name();
                    if (IfMatchParser.HEADER_NAME.equalsIgnoreCase(headerName)
                            && requestHeader.required()) {
                        violations.add(
                                "If-Match 必须声明 required=false，并在资源可见性检查后交给 IfMatchParser: "
                                        + method.getFullName() + " parameter[" + parameter.getIndex() + "]"
                        );
                    }
                }
            }
        }
        return List.copyOf(violations);
    }

    static List<String> moduleBoundaryViolations(JavaClasses classes, String rootPackage) {
        List<String> violations = new ArrayList<>();
        for (JavaClass sourceClass : classes) {
            Optional<PackageLocation> sourceLocation = locate(sourceClass, rootPackage);
            if (sourceLocation.isEmpty()) {
                continue;
            }
            for (Dependency dependency : sourceClass.getDirectDependenciesFromSelf()) {
                Optional<PackageLocation> targetLocation = locate(dependency.getTargetClass(), rootPackage);
                if (targetLocation.isEmpty()) {
                    continue;
                }
                validateDependency(sourceLocation.get(), targetLocation.get(), dependency, violations);
            }
        }
        return List.copyOf(violations);
    }

    private static void validateDependency(
            PackageLocation source,
            PackageLocation target,
            Dependency dependency,
            List<String> violations
    ) {
        if (source.module().equals(target.module())) {
            if (!ALLOWED_SAME_MODULE_LAYER_DEPENDENCIES
                    .getOrDefault(source.layer(), Set.of())
                    .contains(target.layer())) {
                violations.add("非法层级依赖: " + dependency.getDescription());
            }
            return;
        }

        Set<String> allowedTargets = ALLOWED_MODULE_DEPENDENCIES.getOrDefault(source.module(), Set.of());
        if (!allowedTargets.contains(target.module())) {
            violations.add("未允许的模块依赖 " + source.module() + " -> " + target.module()
                    + ": " + dependency.getDescription());
            return;
        }

        if (source.layer().equals("domain") && !target.module().equals("foundation")) {
            violations.add("domain 层不得依赖其他业务模块: " + dependency.getDescription());
            return;
        }

        if (target.module().equals("foundation")) {
            if (target.layer().equals("infrastructure")) {
                violations.add("不得跨模块依赖 foundation.infrastructure: " + dependency.getDescription());
            }
            return;
        }

        if (!target.layer().equals("api")) {
            violations.add("跨模块只能依赖目标模块 api: " + dependency.getDescription());
        }
    }

    private static Optional<PackageLocation> locate(JavaClass javaClass, String rootPackage) {
        String prefix = rootPackage + ".";
        String packageName = javaClass.getPackageName();
        if (!packageName.startsWith(prefix)) {
            return Optional.empty();
        }

        String[] segments = packageName.substring(prefix.length()).split("\\.");
        if (segments.length < 2 || !MODULES.contains(segments[0]) || !LAYERS.contains(segments[1])) {
            return Optional.empty();
        }
        return Optional.of(new PackageLocation(segments[0], segments[1]));
    }

    private record PackageLocation(String module, String layer) {
    }
}
