package com.yanji;

import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

/** Retain the prior Servlet limits for every other multipart endpoint. */
@Configuration
public class PublicUploadWebLimits implements WebMvcConfigurer {
    @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(new HandlerInterceptor(){
        @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) throws Exception {
            if(request.getRequestURI().equals(request.getContextPath()+"/admin/v1/oss/uploads"))return true;
            if(request instanceof MultipartHttpServletRequest){
                long sum=0;
                for(var part:request.getParts()){
                    sum+=part.getSize();
                    if(part.getSize()>15L*1024*1024||sum>25L*1024*1024)throw ContentService.failure(HttpStatus.PAYLOAD_TOO_LARGE,"IMAGE_TOO_LARGE");
                }
            }
            return true;
        }
    });}
}
