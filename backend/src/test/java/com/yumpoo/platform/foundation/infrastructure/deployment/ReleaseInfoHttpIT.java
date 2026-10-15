package com.yumpoo.platform.foundation.infrastructure.deployment;

import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "yumpoo.deployment.release-tag=server-v2026.10.20.1",
                "yumpoo.outbox.enabled=false"
        }
)
class ReleaseInfoHttpIT {

    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Test
    void infoExposesOnlyTheInjectedReleaseTagAndBuildIdentity() throws Exception {
        HttpResponse<String> response = get("/actuator/info");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode info = JsonMapper.builder().build().readTree(response.body());
        assertThat(info.path("release").path("tag").asString()).isEqualTo("server-v2026.10.20.1");
        assertThat(info.path("build").path("commit").asString()).isNotBlank();
        assertThat(info.propertyNames()).containsOnly("release", "build");
    }

    @Test
    void otherActuatorEndpointsStayUnexposed() throws Exception {
        assertThat(get("/actuator/env").statusCode()).isEqualTo(404);
        assertThat(get("/actuator/configprops").statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }
}
