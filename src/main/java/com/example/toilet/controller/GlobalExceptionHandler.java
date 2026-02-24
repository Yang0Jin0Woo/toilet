package com.example.toilet.controller;

import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URI;
import java.util.Set;

@ControllerAdvice(annotations = Controller.class)
public class GlobalExceptionHandler {
    private static final Set<String> ALLOWED_REDIRECT_PREFIXES = Set.of("/", "/map", "/reviews", "/signup");

    private static final String CONFLICT_MESSAGE =
            "?ㅻⅨ ?ъ슜?먭? 癒쇱? ?섏젙?덉뒿?덈떎. 理쒖떊 ?댁슜???ㅼ떆 遺덈윭? 二쇱꽭??";

    @ExceptionHandler({
            OptimisticLockException.class,
            OptimisticLockingFailureException.class
    })
    public String handleOptimisticLock(HttpServletRequest request,
                                       RedirectAttributes redirectAttributes) {

        String retryUrl = buildRetryUrl(request);
        redirectAttributes.addFlashAttribute("warnMessage", CONFLICT_MESSAGE);
        redirectAttributes.addFlashAttribute("skipViewCountOnce", true);
        return "redirect:" + retryUrl;
    }

    private String buildRetryUrl(HttpServletRequest request) {
        String toiletId = request.getParameter("toiletId");
        if (toiletId != null && !toiletId.isBlank()) {
            return "/reviews?toiletId=" + toiletId;
        }

        String referer = request.getHeader("Referer");
        if (referer != null && !referer.isBlank()) {
            String refererPath = extractPathAndQuery(referer);
            String safePath = RedirectSanitizer.toSafePath(refererPath, "/", ALLOWED_REDIRECT_PREFIXES);
            if (!safePath.equals(currentPathAndQuery(request))) {
                return safePath;
            }
        }
        return "/";
    }

    private String extractPathAndQuery(String rawReferer) {
        try {
            URI uri = URI.create(rawReferer);
            String path = uri.getRawPath();
            String query = uri.getRawQuery();
            if (path == null || path.isBlank()) {
                return "/";
            }
            if (query == null || query.isBlank()) {
                return path;
            }
            return path + "?" + query;
        } catch (Exception ignore) {
            return rawReferer;
        }
    }

    private String currentPathAndQuery(HttpServletRequest request) {
        String path = request.getRequestURI();
        String query = request.getQueryString();
        if (query == null || query.isBlank()) {
            return path;
        }
        return path + "?" + query;
    }
}
