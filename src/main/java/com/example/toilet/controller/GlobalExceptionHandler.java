package com.example.toilet.controller;

import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@ControllerAdvice(annotations = Controller.class)
public class GlobalExceptionHandler {

    private static final String CONFLICT_MESSAGE =
            "다른 사용자가 먼저 수정했습니다. 최신 내용을 다시 불러와 주세요.";

    @ExceptionHandler({
            OptimisticLockException.class,
            OptimisticLockingFailureException.class
    })
    public String handleOptimisticLock(HttpServletRequest request,
                                       RedirectAttributes redirectAttributes) {

        String retryUrl = buildRetryUrl(request);
        redirectAttributes.addFlashAttribute("warnMessage", CONFLICT_MESSAGE);
        return "redirect:" + retryUrl;
    }

    private String buildRetryUrl(HttpServletRequest request) {
        String toiletId = request.getParameter("toiletId");
        if (toiletId != null && !toiletId.isBlank()) {
            return "/reviews?toiletId=" + toiletId + "&skipViewCount=true";
        }

        String referer = request.getHeader("Referer");
        if (referer != null && !referer.isBlank()) {
            String current = request.getRequestURL().toString();
            String qs = request.getQueryString();
            if (qs != null && !qs.isBlank()) {
                current = current + "?" + qs;
            }
            if (!referer.equals(current)) {
                return referer;
            }
        }
        return "/";
    }
}
