package com.yumpoo.platform.foundation.consistency;

import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Import(PostgreSqlTestContainerConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "yumpoo.m016.readiness-failure-probe=true",
                "spring.datasource.hikari.connection-timeout=1000"
        }
)
class M016ReadinessFailureIT {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @LocalServerPort
    private int port;

    @org.springframework.beans.factory.annotation.Autowired
    private PostgreSQLContainer postgresContainer;

    @Test
    void databaseFailureMakesReadinessDownWhileLivenessStaysUp() throws Exception {
        assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(200);

        postgresContainer.stop();

        HttpResponse<String> readiness = awaitReadinessFailure();
        HttpResponse<String> liveness = get("/actuator/health/liveness");

        assertThat(readiness.statusCode()).isEqualTo(503);
        assertThat(readiness.body()).isEqualTo("{\"status\":\"DOWN\"}");
        assertThat(liveness.statusCode()).isEqualTo(200);
        assertThat(liveness.body()).isEqualTo("{\"status\":\"UP\"}");
    }

    private HttpResponse<String> awaitReadinessFailure() throws Exception {
        Instant deadline = Instant.now().plusSeconds(20);
        HttpResponse<String> response;
        do {
            response = get("/actuator/health/readiness");
            if (response.statusCode() == 503) {
                return response;
            }
            Thread.sleep(200);
        } while (Instant.now().isBefore(deadline));
        return response;
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
