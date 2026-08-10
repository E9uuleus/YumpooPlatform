package com.yumpoo.platform.foundation.api;

import com.yumpoo.platform.foundation.api.error.ApiErrorResponse;
import com.yumpoo.platform.foundation.api.error.ApiErrorWriter;
import com.yumpoo.platform.foundation.api.error.ApiExceptionHandler;
import com.yumpoo.platform.foundation.api.http.IdempotencyKeyParser;
import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.api.pagination.CursorPageResponse;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageResponse;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.api.web.RequestIdFilter;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.logging.StructuredLoggingContext;
import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.foundation.application.request.RequestIdContext;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ApiContractWebMvcTest.ContractProbeController.class)
@ExtendWith(OutputCaptureExtension.class)
@Import({
        ApiExceptionHandler.class,
        ApiErrorWriter.class,
        RequestIdFilter.class,
        IfMatchParser.class,
        IdempotencyKeyParser.class,
        ApiContractWebMvcTest.ContractProbeController.class
})
class ApiContractWebMvcTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiContractWebMvcTest.class);
    private static final String API = "/api/v1/contract";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApiErrorWriter apiErrorWriter;

    @Autowired
    private RequestIdFilter requestIdFilter;

    static Stream<ErrorCase> errorCases() {
        return Stream.of(
                new ErrorCase(StandardErrorCode.MALFORMED_REQUEST, 400, "400-malformed-request.json"),
                new ErrorCase(StandardErrorCode.AUTHENTICATION_REQUIRED, 401, "401-authentication-required.json"),
                new ErrorCase(StandardErrorCode.ACCOUNT_DISABLED, 403, "403-account-disabled.json"),
                new ErrorCase(StandardErrorCode.ACCESS_DENIED, 403, "403-access-denied.json"),
                new ErrorCase(StandardErrorCode.RESOURCE_NOT_FOUND, 404, "404-resource-not-found.json"),
                new ErrorCase(StandardErrorCode.IDEMPOTENCY_KEY_REUSED, 409, "409-idempotency-key-reused.json"),
                new ErrorCase(StandardErrorCode.REQUEST_IN_PROGRESS, 409, "409-request-in-progress.json"),
                new ErrorCase(StandardErrorCode.INVALID_STATE_TRANSITION, 409, "409-invalid-state-transition.json"),
                new ErrorCase(StandardErrorCode.WORKLOG_LOCKED, 409, "409-worklog-locked.json"),
                new ErrorCase(StandardErrorCode.VERSION_CONFLICT, 412, "412-version-conflict.json"),
                new ErrorCase(StandardErrorCode.VALIDATION_FAILED, 422, "422-validation-failed.json"),
                new ErrorCase(StandardErrorCode.CLIENT_UPGRADE_REQUIRED, 426, "426-client-upgrade-required.json"),
                new ErrorCase(StandardErrorCode.PRECONDITION_REQUIRED, 428, "428-precondition-required.json"),
                new ErrorCase(StandardErrorCode.DEPENDENCY_UNAVAILABLE, 503, "503-dependency-unavailable.json")
        );
    }

    @ParameterizedTest
    @MethodSource("errorCases")
    void applicationErrorsMatchTheCheckedInGoldenExamples(ErrorCase errorCase) throws Exception {
        JsonNode expected = goldenError(errorCase.fileName());
        String requestId = expected.get("requestId").asString();

        MvcResult result = mockMvc.perform(get(API + "/errors/{code}", errorCase.code())
                        .header(RequestIdContext.HEADER_NAME, requestId))
                .andExpect(status().is(errorCase.httpStatus()))
                .andExpect(header().string(RequestIdContext.HEADER_NAME, requestId))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        assertThat(readJson(result)).isEqualTo(expected);
    }

    @Test
    void writerUsesTheSameGoldenErrorShapeOutsideMvcAdvice() throws Exception {
        JsonNode expected = goldenError("412-version-conflict.json");
        String requestId = expected.get("requestId").asString();
        MockHttpServletResponse response = new MockHttpServletResponse();

        apiErrorWriter.write(
                response,
                new ApplicationException(StandardErrorCode.VERSION_CONFLICT),
                requestId
        );

        assertThat(response.getStatus()).isEqualTo(412);
        assertThat(response.getHeader(RequestIdContext.HEADER_NAME)).isEqualTo(requestId);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isNull();
        assertThat(objectMapper.readTree(response.getContentAsString())).isEqualTo(expected);
    }

    @Test
    void requestInProgressWriterIncludesTheStableRetryAfterHeader() throws Exception {
        JsonNode expected = goldenError("409-request-in-progress.json");
        String requestId = expected.get("requestId").asString();
        MockHttpServletResponse response = new MockHttpServletResponse();

        apiErrorWriter.write(
                response,
                new ApplicationException(StandardErrorCode.REQUEST_IN_PROGRESS),
                requestId
        );

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(objectMapper.readTree(response.getContentAsString())).isEqualTo(expected);
    }

    @Test
    void requestInProgressAdviceIncludesRetryAfterButOtherConflictsDoNot() throws Exception {
        mockMvc.perform(get(API + "/errors/{code}", StandardErrorCode.REQUEST_IN_PROGRESS))
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"));

        mockMvc.perform(get(API + "/errors/{code}", StandardErrorCode.IDEMPOTENCY_KEY_REUSED))
                .andExpect(status().isConflict())
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void trustedLoopbackRequestIdIsPreservedOnSuccessfulResponses() throws Exception {
        String trustedRequestId = "proxy.request-123:abc";

        mockMvc.perform(get(API + "/request-id")
                        .header(RequestIdContext.HEADER_NAME, trustedRequestId))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdContext.HEADER_NAME, trustedRequestId))
                .andExpect(jsonPath("$.requestId").value(trustedRequestId));
    }

    @Test
    void untrustedRemoteRequestIdIsReplacedWithAServerUuid() throws Exception {
        MvcResult result = mockMvc.perform(get(API + "/request-id")
                        .header(RequestIdContext.HEADER_NAME, "attacker-controlled")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.8");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andReturn();

        String actual = result.getResponse().getHeader(RequestIdContext.HEADER_NAME);
        assertThat(actual).isNotEqualTo("attacker-controlled");
        assertThat(UUID.fromString(actual)).isNotNull();
        assertThat(readJson(result).get("requestId").asString()).isEqualTo(actual);
    }

    @Test
    void unsafeLoopbackRequestIdIsReplacedWithAServerUuid() throws Exception {
        MvcResult result = mockMvc.perform(get(API + "/request-id")
                        .header(RequestIdContext.HEADER_NAME, "unsafe request id"))
                .andExpect(status().isOk())
                .andReturn();

        String actual = result.getResponse().getHeader(RequestIdContext.HEADER_NAME);
        assertThat(UUID.fromString(actual)).isNotNull();
        assertThat(actual).isNotEqualTo("unsafe request id");
    }

    @Test
    void unknownRequestFieldsAreRejectedInsteadOfSilentlyIgnored() throws Exception {
        JsonNode expected = goldenError("400-malformed-request.json");
        String requestId = expected.get("requestId").asString();

        MvcResult result = mockMvc.perform(post(API + "/body")
                        .header(RequestIdContext.HEADER_NAME, requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"valid\",\"rowVersion\":7}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(readJson(result)).isEqualTo(expected);
    }

    @Test
    void bodyValidationProducesStableFieldErrorsWithoutRejectedValues() throws Exception {
        MvcResult result = mockMvc.perform(post(API + "/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("NOTBLANK"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("rejectedValue");
    }

    @Test
    void defaultOffsetPageAndEmptyCursorPageMatchGoldenExamples() throws Exception {
        MvcResult page = mockMvc.perform(get(API + "/page"))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult cursorPage = mockMvc.perform(get(API + "/cursor-page"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(readJson(page)).isEqualTo(goldenPagination("empty-page.json"));
        assertThat(readJson(cursorPage)).isEqualTo(goldenPagination("empty-cursor-page.json"));
    }

    @Test
    void offsetPaginationEnforcesTheHardBounds() throws Exception {
        mockMvc.perform(get(API + "/page").queryParam("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));

        mockMvc.perform(get(API + "/page").queryParam("size", "101"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("size"));

        mockMvc.perform(get(API + "/page").queryParam("page", "-1"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("page"));
    }

    @Test
    void offsetPageRecordConstructorEnforcesItsOwnInvariants() {
        assertThat(new OffsetPageRequest(OffsetPageRequest.MIN_PAGE, OffsetPageRequest.MIN_SIZE))
                .isEqualTo(new OffsetPageRequest(0, 1));
        assertThatThrownBy(() -> new OffsetPageRequest(-1, OffsetPageRequest.DEFAULT_SIZE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OffsetPageRequest(0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OffsetPageRequest(0, OffsetPageRequest.MAX_SIZE + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invisibleResourceAlwaysReturns404BeforeIfMatchValidation() throws Exception {
        mockMvc.perform(get(API + "/if-match")
                        .queryParam("visible", "false"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(get(API + "/if-match")
                        .queryParam("visible", "false")
                        .header(IfMatchParser.HEADER_NAME, "W/\"9\""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void visibleResourceValidatesRequiredAndStrongIfMatch() throws Exception {
        mockMvc.perform(get(API + "/if-match")
                        .queryParam("visible", "true"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));

        mockMvc.perform(get(API + "/if-match")
                        .queryParam("visible", "true")
                        .header(IfMatchParser.HEADER_NAME, "W/\"9\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        mockMvc.perform(get(API + "/if-match")
                        .queryParam("visible", "true")
                        .header(IfMatchParser.HEADER_NAME, "\"0009\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowVersion").value(9));

        assertThat(IfMatchParser.format(9)).isEqualTo("\"9\"");
    }

    @Test
    void visibleResourceTreatsEveryPresentButInvalidIfMatchAsMalformed() throws Exception {
        for (String invalid : List.of(
                "",
                " ",
                "*",
                "W/\"9\"",
                "\"1\", \"2\"",
                "9",
                "\"9223372036854775808\""
        )) {
            mockMvc.perform(get(API + "/if-match")
                            .queryParam("visible", "true")
                            .header(IfMatchParser.HEADER_NAME, invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }
    }

    @Test
    void requiredHeaderBindingCannotBypassVisibleLookupToProduce428() throws Exception {
        mockMvc.perform(get(API + "/if-match-required"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void requestIdFilterHasTheFirstOrder() {
        Order order = RequestIdFilter.class.getAnnotation(Order.class);

        assertThat(order).isNotNull();
        assertThat(order.value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void requestFilterScopesRootCorrelationAndMdcWithoutThreadLeakage() throws Exception {
        String requestId = "proxy.filter-correlation";
        MockHttpServletRequest request = loopbackRequest(requestId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<RequestCorrelation> observed = new AtomicReference<>();
        AtomicReference<Map<String, String>> observedMdc = new AtomicReference<>();
        Filter observingFilter = (filterRequest, filterResponse, chain) -> {
            observed.set(RequestCorrelationContext.required());
            observedMdc.set(MDC.getCopyOfContextMap());
        };

        requestIdFilter.doFilter(
                request,
                response,
                new MockFilterChain(new HttpServlet() { }, observingFilter)
        );

        assertThat(observed.get()).isEqualTo(RequestCorrelation.root(requestId));
        assertThat(observedMdc.get())
                .containsEntry(StructuredLoggingContext.REQUEST_ID, requestId)
                .containsEntry(StructuredLoggingContext.CORRELATION_ID, requestId);
        assertThat(RequestCorrelationContext.current()).isEmpty();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void structuredLogIsJsonWithControlledMdcAndWithoutExceptionMessage(
            CapturedOutput output
    ) throws Exception {
        IllegalStateException failure = new IllegalStateException(
                "payload=secret-do-not-log database-password=hidden"
        );
        try (StructuredLoggingContext.Scope ignored = StructuredLoggingContext.open(Map.of(
                StructuredLoggingContext.REQUEST_ID, "m011-log-request",
                StructuredLoggingContext.CORRELATION_ID, "m011-log-request",
                StructuredLoggingContext.EVENT_ID, "00000000-0000-4000-8000-000000000011",
                StructuredLoggingContext.CONSUMER_NAME, "audit.m011_probe_projection",
                StructuredLoggingContext.ATTEMPT, 2,
                StructuredLoggingContext.OUTCOME, "RETRY",
                StructuredLoggingContext.ERROR_CODE, "M011_RETRYABLE_FAILURE"
        ))) {
            LOGGER.warn(
                    "m011 structured logging probe; exceptionType={}",
                    failure.getClass().getName()
            );
        }

        String logLine = output.getOut().lines()
                .filter(line -> line.contains("m011 structured logging probe"))
                .reduce((first, second) -> second)
                .orElseThrow();
        JsonNode log = objectMapper.readTree(logLine);
        assertThat(log.get("requestId").asString()).isEqualTo("m011-log-request");
        assertThat(log.get("correlationId").asString()).isEqualTo("m011-log-request");
        assertThat(log.get("consumerName").asString())
                .isEqualTo("audit.m011_probe_projection");
        assertThat(log.get("attempt").asString()).isEqualTo("2");
        assertThat(log.get("outcome").asString()).isEqualTo("RETRY");
        assertThat(log.get("errorCode").asString()).isEqualTo("M011_RETRYABLE_FAILURE");
        assertThat(logLine)
                .doesNotContain("secret-do-not-log")
                .doesNotContain("database-password=hidden");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void earlyApplicationFailureUsesTheSameSafeErrorWriter() throws Exception {
        String requestId = "proxy.filter-application";
        MockHttpServletRequest request = loopbackRequest(requestId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        Filter failingFilter = (filterRequest, filterResponse, chain) -> {
            throw new ApplicationException(StandardErrorCode.REQUEST_IN_PROGRESS);
        };

        requestIdFilter.doFilter(
                request,
                response,
                new MockFilterChain(new HttpServlet() { }, failingFilter)
        );

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getHeader(RequestIdContext.HEADER_NAME)).isEqualTo(requestId);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(body.get("requestId").asString()).isEqualTo(requestId);
        assertThat(body.get("code").asString()).isEqualTo("REQUEST_IN_PROGRESS");
    }

    @Test
    void earlyUnexpectedFailureResetsMetadataAndDoesNotLeakTheException() throws Exception {
        String requestId = "proxy.filter-unexpected";
        MockHttpServletRequest request = loopbackRequest(requestId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        Filter failingFilter = (filterRequest, filterResponse, chain) -> {
            HttpServletResponse httpResponse = (HttpServletResponse) filterResponse;
            httpResponse.setHeader(HttpHeaders.ETAG, "\"secret-version\"");
            httpResponse.getOutputStream().write("partial-secret".getBytes(StandardCharsets.UTF_8));
            throw new IllegalStateException("database-password=do-not-leak");
        };

        requestIdFilter.doFilter(
                request,
                response,
                new MockFilterChain(new HttpServlet() { }, failingFilter)
        );

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getHeader(HttpHeaders.ETAG)).isNull();
        assertThat(response.getHeader(RequestIdContext.HEADER_NAME)).isEqualTo(requestId);
        assertThat(body.get("requestId").asString()).isEqualTo(requestId);
        assertThat(body.get("code").asString()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getContentAsString())
                .doesNotContain("partial-secret")
                .doesNotContain("database-password")
                .doesNotContain("IllegalStateException");
        assertThat(RequestCorrelationContext.current()).isEmpty();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void committedEarlyFailureIsRethrownUnchanged() throws Exception {
        IllegalStateException failure = new IllegalStateException("committed failure");
        MockHttpServletRequest request = loopbackRequest("proxy.filter-committed");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Filter failingFilter = (filterRequest, filterResponse, chain) -> {
            filterResponse.flushBuffer();
            throw failure;
        };
        MockFilterChain chain = new MockFilterChain(new HttpServlet() { }, failingFilter);

        assertThatThrownBy(() -> requestIdFilter.doFilter(request, response, chain))
                .isSameAs(failure);
        assertThat(response.isCommitted()).isTrue();
        assertThat(RequestCorrelationContext.current()).isEmpty();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void idempotencyKeyMustBeACanonicalUuid() throws Exception {
        String key = "75A6C9A7-4166-46EA-A249-F817929D137D";

        mockMvc.perform(get(API + "/idempotency-key")
                        .header(IdempotencyKeyParser.HEADER_NAME, key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotencyKey").value(key.toLowerCase()));

        mockMvc.perform(get(API + "/idempotency-key")
                        .header(IdempotencyKeyParser.HEADER_NAME, "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void unknownApiRouteUsesTheSafe404GoldenResponse() throws Exception {
        JsonNode expected = goldenError("404-resource-not-found.json");
        String requestId = expected.get("requestId").asString();

        MvcResult result = mockMvc.perform(get(API + "/does-not-exist")
                        .header(RequestIdContext.HEADER_NAME, requestId))
                .andExpect(status().isNotFound())
                .andReturn();

        assertThat(readJson(result)).isEqualTo(expected);
    }

    @Test
    void unexpectedFailuresReturnOnlyTheSafe500Body() throws Exception {
        MvcResult result = mockMvc.perform(get(API + "/unexpected-failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("系统暂时无法处理请求"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(jsonPath("$.details").isEmpty())
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("database-password")
                .doesNotContain("IllegalStateException")
                .doesNotContain("org.springframework")
                .doesNotContain("C:\\secret");
    }

    private JsonNode goldenError(String fileName) throws IOException {
        return objectMapper.readTree(Files.readString(
                contractPath("examples", "errors", fileName),
                StandardCharsets.UTF_8
        ));
    }

    private JsonNode goldenPagination(String fileName) throws IOException {
        return objectMapper.readTree(Files.readString(
                contractPath("examples", "pagination", fileName),
                StandardCharsets.UTF_8
        ));
    }

    private static Path contractPath(String... segments) {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path contracts = Files.isDirectory(workingDirectory.resolve("contracts"))
                ? workingDirectory.resolve("contracts")
                : workingDirectory.resolve("..").resolve("contracts").normalize();
        Path resolved = contracts;
        for (String segment : segments) {
            resolved = resolved.resolve(segment);
        }
        return resolved;
    }

    private JsonNode readJson(MvcResult result) throws IOException {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static MockHttpServletRequest loopbackRequest(String requestId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader(RequestIdContext.HEADER_NAME, requestId);
        return request;
    }

    private record ErrorCase(StandardErrorCode code, int httpStatus, String fileName) {
    }

    private record ProbeBody(@NotBlank(message = "名称不能为空") String name) {
    }

    @ApiV1Controller
    static final class ContractProbeController {

        private final IfMatchParser ifMatchParser;
        private final IdempotencyKeyParser idempotencyKeyParser;

        ContractProbeController(
                IfMatchParser ifMatchParser,
                IdempotencyKeyParser idempotencyKeyParser
        ) {
            this.ifMatchParser = ifMatchParser;
            this.idempotencyKeyParser = idempotencyKeyParser;
        }

        @GetMapping("/contract/errors/{code}")
        void error(@PathVariable StandardErrorCode code) {
            if (code == StandardErrorCode.VALIDATION_FAILED) {
                throw new ApplicationException(
                        code,
                        code.defaultMessage(),
                        List.of(new FieldViolation(
                                "to",
                                "DATE_RANGE_TOO_LARGE",
                                "日期范围最多包含 366 个自然日"
                        ))
                );
            }
            throw new ApplicationException(code);
        }

        @GetMapping("/contract/request-id")
        Map<String, String> requestId(HttpServletRequest request) {
            return Map.of(
                    "requestId",
                    (String) request.getAttribute(RequestIdContext.ATTRIBUTE_NAME)
            );
        }

        @PostMapping("/contract/body")
        ProbeBody body(@Valid @RequestBody ProbeBody body) {
            return body;
        }

        @GetMapping("/contract/page")
        OffsetPageResponse<Object> page(
                @RequestParam(required = false) Integer page,
                @RequestParam(required = false) Integer size
        ) {
            OffsetPageRequest request = OffsetPageRequest.of(page, size);
            return OffsetPageResponse.of(List.of(), request, 0);
        }

        @GetMapping("/contract/cursor-page")
        CursorPageResponse<Object> cursorPage() {
            return new CursorPageResponse<>(List.of(), null);
        }

        @GetMapping("/contract/if-match")
        Map<String, Long> ifMatch(
                @RequestParam boolean visible,
                @RequestHeader(value = IfMatchParser.HEADER_NAME, required = false) String ifMatch
        ) {
            return Map.of("rowVersion", ifMatchParser.parseForVisibleResource(visible, ifMatch));
        }

        @GetMapping("/contract/if-match-required")
        Map<String, Long> ifMatchRequired(
                @RequestHeader(IfMatchParser.HEADER_NAME) String ifMatch
        ) {
            return Map.of("rowVersion", ifMatchParser.parseForVisibleResource(true, ifMatch));
        }

        @GetMapping("/contract/idempotency-key")
        Map<String, String> idempotencyKey(
                @RequestHeader(value = IdempotencyKeyParser.HEADER_NAME, required = false) String key
        ) {
            return Map.of("idempotencyKey", idempotencyKeyParser.parseRequired(key).toString());
        }

        @GetMapping("/contract/unexpected-failure")
        void unexpectedFailure() {
            throw new IllegalStateException(
                    "database-password=do-not-leak at C:\\secret\\internal.sql"
            );
        }
    }
}
