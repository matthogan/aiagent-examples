package com.example.javaaiagent.security;

import com.example.javaaiagent.config.RuntimeSettings;
import com.example.javaaiagent.templates.MessageTemplates;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * Per-process admission control. Production still needs global gateway limits.
 */
final class AdmissionFilter extends OncePerRequestFilter {

    private final MessageTemplates templates;
    private final Semaphore slots;

    AdmissionFilter(MessageTemplates templates, RuntimeSettings runtime) {
        this.templates = templates;
        this.slots = new Semaphore(runtime.workers());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = UUID.randomUUID().toString();
        request.setAttribute("requestId", id);
        response.setHeader("X-Request-ID", id);
        response.setHeader("Cache-Control", "no-store");
        long start = System.nanoTime();
        boolean acquired = slots.tryAcquire();
        try {
            if (!acquired) {
                response.setStatus(429);
                response.setContentType("application/json");
                response.getWriter().write(templates.json("error-busy"));
            } else {
                chain.doFilter(request, response);
            }
        } finally {
            if (acquired) {
                slots.release();
            }
            LoggerFactory.getLogger("agent.audit").info("event=http_request request_id={} status={} duration_ms={}",
                    id, response.getStatus(), (System.nanoTime() - start) / 1_000_000);
        }
    }
}
