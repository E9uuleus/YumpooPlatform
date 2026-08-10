package com.yumpoo.platform.foundation.api.error;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.request.RequestIdContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 将 MVC 与应用层异常收敛为唯一的 Yumpoo 错误体。
 */
@RestControllerAdvice
public final class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final MediaType JSON_UTF8 = new MediaType(
            MediaType.APPLICATION_JSON,
            StandardCharsets.UTF_8
    );

    @ExceptionHandler(ApplicationException.class)
    ResponseEntity<Object> handleApplicationException(
            ApplicationException exception,
            WebRequest request
    ) {
        String requestId = requestId(request);
        return response(
                ApiErrorResponses.from(exception, requestId),
                ApiErrorStatus.forCode(exception.errorCode()),
                requestId
        );
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpectedException(Exception ignored, WebRequest request) {
        return response(StandardErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, request, List.of());
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        return response(StandardErrorCode.MALFORMED_REQUEST, HttpStatus.BAD_REQUEST, request, List.of());
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        return response(StandardErrorCode.MALFORMED_REQUEST, HttpStatus.BAD_REQUEST, request, List.of());
    }

    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(
            ServletRequestBindingException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        return response(StandardErrorCode.MALFORMED_REQUEST, HttpStatus.BAD_REQUEST, request, List.of());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        List<ApiFieldError> fieldErrors = new ArrayList<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            fieldErrors.add(new ApiFieldError(
                    fieldError.getField(),
                    validationCode(fieldError),
                    validationMessage(fieldError)
            ));
        }
        for (ObjectError globalError : exception.getBindingResult().getGlobalErrors()) {
            fieldErrors.add(new ApiFieldError(
                    "request",
                    validationCode(globalError),
                    validationMessage(globalError)
            ));
        }
        return response(
                StandardErrorCode.VALIDATION_FAILED,
                HttpStatus.UNPROCESSABLE_CONTENT,
                request,
                fieldErrors
        );
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        List<ApiFieldError> fieldErrors = new ArrayList<>();
        for (ParameterValidationResult result : exception.getParameterValidationResults()) {
            String parameterName = result.getMethodParameter().getParameterName();
            String field = parameterName == null || parameterName.isBlank() ? "request" : parameterName;
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                fieldErrors.add(new ApiFieldError(
                        field,
                        validationCode(error),
                        validationMessage(error)
                ));
            }
        }
        for (MessageSourceResolvable error : exception.getCrossParameterValidationResults()) {
            fieldErrors.add(new ApiFieldError(
                    "request",
                    validationCode(error),
                    validationMessage(error)
            ));
        }
        return response(
                StandardErrorCode.VALIDATION_FAILED,
                HttpStatus.UNPROCESSABLE_CONTENT,
                request,
                fieldErrors
        );
    }

    @Override
    protected ResponseEntity<Object> handleNoHandlerFoundException(
            NoHandlerFoundException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        return response(StandardErrorCode.RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND, request, List.of());
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        return response(StandardErrorCode.RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND, request, List.of());
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        StandardErrorCode code = status.is5xxServerError()
                ? StandardErrorCode.INTERNAL_ERROR
                : StandardErrorCode.MALFORMED_REQUEST;
        return response(code, status, request, List.of());
    }

    private static ResponseEntity<Object> response(
            StandardErrorCode code,
            HttpStatusCode status,
            WebRequest request,
            List<ApiFieldError> fieldErrors
    ) {
        String requestId = requestId(request);
        ApiErrorResponse body = ApiErrorResponses.create(
                code,
                code.defaultMessage(),
                requestId,
                fieldErrors
        );
        return response(body, status, requestId);
    }

    private static ResponseEntity<Object> response(
            ApiErrorResponse body,
            HttpStatusCode status,
            String requestId
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(JSON_UTF8);
        ApiErrorHeaders.apply(headers, body);
        return new ResponseEntity<>(body, headers, status);
    }

    private static String requestId(WebRequest request) {
        if (request instanceof ServletWebRequest servletWebRequest) {
            HttpServletRequest servletRequest = servletWebRequest.getRequest();
            Object value = servletRequest.getAttribute(RequestIdContext.ATTRIBUTE_NAME);
            if (value instanceof String requestId && !requestId.isBlank()) {
                return requestId;
            }
            String generated = UUID.randomUUID().toString();
            servletRequest.setAttribute(RequestIdContext.ATTRIBUTE_NAME, generated);
            return generated;
        }
        return UUID.randomUUID().toString();
    }

    private static String validationCode(MessageSourceResolvable error) {
        String[] codes = error.getCodes();
        String code = codes == null || codes.length == 0 ? "INVALID" : codes[codes.length - 1];
        return code.replaceAll("[^A-Za-z0-9]+", "_").toUpperCase(Locale.ROOT);
    }

    private static String validationMessage(MessageSourceResolvable error) {
        String message = error.getDefaultMessage();
        return message == null || message.isBlank() ? "字段值不符合要求" : message;
    }
}
