package com.yanji;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticate and reserve the single upload slot before Servlet multipart parsing writes temporary files. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE+30)
public final class PublicUploadAdmissionFilter extends OncePerRequestFilter {
    private final AdminAccess access;private final ObjectMapper json;private final Semaphore slot=new Semaphore(1);
    public PublicUploadAdmissionFilter(AdminAccess access,ObjectMapper json){this.access=access;this.json=json;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        boolean own=request.getRequestURI().equals(request.getContextPath()+"/admin/v1/oss/uploads")&&request.getMethod().equals("POST");
        if(!own){
            String type=request.getContentType();
            if(type!=null&&type.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/")&&request.getContentLengthLong()>25L*1024*1024){error(response,413,"IMAGE_TOO_LARGE");return;}
            chain.doFilter(request,response);return;
        }
        var previous=RequestContextHolder.getRequestAttributes();var attributes=new ServletRequestAttributes(request,response);
        RequestContextHolder.setRequestAttributes(attributes);
        try {
            try{access.require("content:edit");}catch(RegistrationService.Failure e){error(response,e.status.value(),e.code);return;}
            if(request.getContentLengthLong()>52L*1024*1024){error(response,413,"PUBLIC_UPLOAD_SIZE_INVALID");return;}
            if(!slot.tryAcquire()){error(response,429,"OSS_UPLOAD_BUSY");return;}
            try{chain.doFilter(request,response);}finally{slot.release();}
        }finally{attributes.requestCompleted();if(previous==null)RequestContextHolder.resetRequestAttributes();else RequestContextHolder.setRequestAttributes(previous);}
    }
    private void error(HttpServletResponse response,int status,String code) throws IOException {
        response.setStatus(status);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");response.setHeader("Cache-Control","no-store");
        if(status==401)response.setHeader("WWW-Authenticate","Bearer realm=\"xcwc-admin\"");
        if(status==429)response.setHeader("Retry-After","1");
        json.writeValue(response.getOutputStream(),Map.of("code",code,"message",code));
    }
}
