package com.yumpoo.platform.foundation.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.yumpoo.platform.foundation.testing.M010ProbeController;
import com.yumpoo.platform.foundation.testing.M011ProbeController;
import com.yumpoo.platform.testing.HttpIntegrationTest;
import com.yumpoo.platform.testing.OpenApiContract;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@HttpIntegrationTest
class OpenApiRouteInventoryIT {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void controllerRoutesMatchOpenApiOperations() {
        Set<String> routes = new TreeSet<>();
        Set<String> testRoutes = new TreeSet<>();
        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            var mapping = entry.getKey();
            Class<?> controller = entry.getValue().getBeanType();
            Set<String> target = controller == M010ProbeController.class || controller == M011ProbeController.class
                    ? testRoutes
                    : routes;
            for (String path : mapping.getPatternValues()) {
                if (path.startsWith("/api/v1/")) {
                    mapping.getMethodsCondition()
                            .getMethods()
                            .forEach(method -> target.add(operation(method.name(), path)));
                }
            }
        }

        OpenAPI spec = new OpenAPIV3Parser()
                .read(OpenApiContract.specification().toUri().toString());
        String base = spec.getServers().getFirst().getUrl();
        Set<String> contract = new TreeSet<>();
        spec.getPaths()
                .forEach((path, item) -> item.readOperationsMap()
                        .keySet()
                        .forEach(method -> contract.add(operation(method.name(), base + path))));

        Set<String> onlyInCode = new TreeSet<>(routes);
        onlyInCode.removeAll(contract);
        Set<String> onlyInContract = new TreeSet<>(contract);
        onlyInContract.removeAll(routes);
        assertThat(routes)
                .withFailMessage("只在代码中：%s%n只在契约中：%s", onlyInCode, onlyInContract)
                .isEqualTo(contract);
        assertThat(testRoutes)
                .containsExactlyInAnyOrder(
                        "PATCH /api/v1/__test/m0-10/probes/{}",
                        "POST /api/v1/__test/m0-10/probes",
                        "POST /api/v1/__test/m0-11/probes");
    }

    private static String operation(String method, String path) {
        return method + " " + path.replaceAll("\\{[^}]+}", "{}");
    }
}
