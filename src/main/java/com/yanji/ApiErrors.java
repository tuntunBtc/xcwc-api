package com.yanji;

import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<?> oversized(Exception error) {
        return ResponseEntity.status(413).body(Map.of("code","IMAGE_TOO_LARGE","message","照片超出上传大小限制"));
    }
    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    ResponseEntity<?> missingPart(Exception error) {
        return ResponseEntity.badRequest().body(Map.of("code","INVALID_CHECKIN","message","请提交照片、合照与出镜信息"));
    }
    @ExceptionHandler(RegistrationService.Failure.class)
    ResponseEntity<?> failure(RegistrationService.Failure error) {
        var response=ResponseEntity.status(error.status);
        if(error.status.value()==401)
            response.header("WWW-Authenticate", "Bearer realm=\""+("ADMIN_LOGIN_REQUIRED".equals(error.code)?"xcwc-admin":"xcwc")+"\"");
        return response.body(Map.of("code", error.code, "message", error.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid(Exception error) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_INPUT", "message", "输入格式不正确，请检查账号、密码或领养码"));
    }
    @ExceptionHandler({org.springframework.web.servlet.resource.NoResourceFoundException.class,
                       org.springframework.web.servlet.NoHandlerFoundException.class})
    ResponseEntity<?> notFound(Exception error) {
        return ResponseEntity.status(404).body(Map.of("code", "ENDPOINT_NOT_FOUND", "message", "接口不存在，请检查请求路径"));
    }
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> method(org.springframework.web.HttpRequestMethodNotSupportedException error) {
        return ResponseEntity.status(405).allow(error.getSupportedHttpMethods().toArray(org.springframework.http.HttpMethod[]::new))
            .body(Map.of("code","METHOD_NOT_ALLOWED","message","请求方法不支持"));
    }
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<?> mediaType(org.springframework.web.HttpMediaTypeNotSupportedException error) {
        return ResponseEntity.status(415)
            .body(Map.of("code","UNSUPPORTED_MEDIA_TYPE","message","请求内容类型不支持，请按接口要求提交 JSON 或表单"));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception error) {
        LoggerFactory.getLogger(ApiErrors.class).error("API failure: {}", error.getClass().getName());
        return ResponseEntity.internalServerError().body(Map.of("code", "SERVICE_ERROR", "message", "服务暂时不可用，请稍后重试"));
    }
}
