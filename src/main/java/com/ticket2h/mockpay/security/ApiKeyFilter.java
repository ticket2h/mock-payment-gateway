package com.ticket2h.mockpay.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

import com.ticket2h.mockpay.config.MockPayProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Mọi request tới {@code /api/**} phải có header {@code X-Api-Key} đúng, nếu không trả {@code 401}.
 * {@code /admin/**} và {@code /pay/**} không cần key (chỉ dùng local).
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";

    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

    private final byte[] expectedKey;
    private final JsonMapper jsonMapper;

    public ApiKeyFilter(MockPayProperties properties, JsonMapper jsonMapper) {
        this.expectedKey = properties.apiKey().getBytes(StandardCharsets.UTF_8);
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = PATH_HELPER.getPathWithinApplication(request).replaceAll("/{2,}", "/");
        return !(path.equals("/api") || path.startsWith("/api/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (provided != null && MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expectedKey)) {
            chain.doFilter(request, response);
            return;
        }
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", HttpStatus.UNAUTHORIZED.getReasonPhrase());
        problem.put("status", HttpStatus.UNAUTHORIZED.value());
        problem.put("detail", provided == null ? "Missing " + HEADER + " header" : "Invalid " + HEADER);
        problem.put("instance", request.getRequestURI());
        problem.put("code", "UNAUTHORIZED");

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getOutputStream().write(jsonMapper.writeValueAsBytes(problem));
    }
}
