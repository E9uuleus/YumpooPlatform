package com.yumpoo.platform.testing;

import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

class OpenApiResponseValidationFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingResponseWrapper cached = new ContentCachingResponseWrapper(response);
        chain.doFilter(request, cached);

        SimpleResponse.Builder builder = SimpleResponse.Builder.status(cached.getStatus());
        if (cached.getContentType() != null) {
            builder.withContentType(cached.getContentType());
        }
        for (String header : cached.getHeaderNames()) {
            if (!HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(header)) {
                builder.withHeader(header, List.copyOf(cached.getHeaders(header)));
            }
        }
        builder.withBody(cached.getContentAsByteArray());

        String path = request.getRequestURI();
        Request.Method method = Request.Method.valueOf(request.getMethod());
        ValidationReport report = OpenApiContract.VALIDATOR.validateResponse(path, method, builder.build());
        for (ValidationReport.Message message : report.getMessages()) {
            if (cached.getStatus() == 404 && "validation.request.path.missing".equals(message.getKey())) {
                continue;
            }
            if (message.getLevel() == ValidationReport.Level.ERROR) {
                OpenApiResponseRecorder.record(method + " " + path + " → " + cached.getStatus() + "：" + message.getKey()
                        + " " + message.getMessage());
            }
        }
        cached.copyBodyToResponse();
    }
}
