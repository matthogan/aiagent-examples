package com.example.javaaiagent.security;

import com.example.javaaiagent.config.AgentSettings;
import com.example.javaaiagent.config.RuntimeSettings;
import com.example.javaaiagent.templates.MessageTemplates;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration
public class SecurityConfiguration {
    /**
     * Build the verifier once at startup; never choose verification keys from an incoming token.
     */
    static AuthenticationManager authenticationManager(AgentSettings settings) throws Exception {
        NimbusJwtDecoder decoder = settings.authMode().equals("jwt") ? jwtDecoder(settings) : null;
        return authentication -> {
            String token = ((BearerTokenAuthenticationToken) authentication).getToken();
            Caller caller = decoder == null ? demoCaller(token, settings) : jwtCaller(token, decoder);
            return UsernamePasswordAuthenticationToken.authenticated(caller, null,
                    caller.scopes().stream().map(SimpleGrantedAuthority::new).toList());
        };
    }

    private static NimbusJwtDecoder jwtDecoder(AgentSettings settings) throws Exception {
        NimbusJwtDecoder decoder;
        try (var key = Files.newInputStream(Path.of(settings.jwtPublicKeyFile()))) {
            decoder = NimbusJwtDecoder.withPublicKey(RsaKeyConverters.x509().convert(key))
                    .signatureAlgorithm(SignatureAlgorithm.RS256)
                    .build();
        }
        // Spring validates issuer and time windows; this example also requires explicit claims.
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(settings.jwtIssuer()),
                token -> requiredClaims(token, settings.jwtAudience())));
        return decoder;
    }

    private static OAuth2TokenValidatorResult requiredClaims(Jwt token, String audience) {
        boolean valid = token.getExpiresAt() != null
                && token.getIssuedAt() != null
                && token.getIssuedAt().isBefore(Instant.now().plusSeconds(60))
                && token.getSubject() != null
                && !token.getSubject().isBlank()
                && token.getAudience().contains(audience);
        return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "Required claims missing or invalid", null));
    }

    private static Caller demoCaller(String token, AgentSettings settings) {
        if (!constantEquals(token, settings.demoToken())) {
            throw new BadCredentialsException("Invalid bearer token");
        }
        // These public fixture grants are only reachable when startup validation permits demo mode.
        return new Caller("local-demo",
                Set.of("agent:invoke", "status:read", "runbooks:read"),
                Set.of("payments", "orders"));
    }

    private static Caller jwtCaller(String token, NimbusJwtDecoder decoder) {
        try {
            return callerFromClaims(decoder.decode(token));
        } catch (JwtException | IllegalArgumentException ex) {
            // Do not reveal signature details or token contents in the authentication error.
            throw new BadCredentialsException("Invalid bearer token");
        }
    }

    /**
     * Only verified claims become grants; malformed claim types must not be coerced into strings.
     */
    private static Caller callerFromClaims(Jwt jwt) {
        Object scope = jwt.getClaims().getOrDefault("scope", "");
        Object services = jwt.getClaims().getOrDefault("services", List.of());
        if (!(scope instanceof String scopeString)
                || !(services instanceof List<?> grants)
                || grants.stream().anyMatch(s -> !(s instanceof String))) {
            throw new BadCredentialsException("Invalid authorization claims");
        }
        return new Caller(
                jwt.getSubject(),
                Arrays.stream(scopeString.split("\\s+"))
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.toSet()),
                grants.stream().map(String.class::cast).collect(Collectors.toSet()));
    }

    static boolean constantEquals(String supplied, String expected) {
        return MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, AgentSettings settings, MessageTemplates templates, RuntimeSettings runtime)
            throws Exception {
        var manager = authenticationManager(settings);
        return http.csrf(csrf -> csrf.disable()) // Bearer headers only; no cookie authentication.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .authorizeHttpRequests(a -> a.requestMatchers("/healthz")
                        .permitAll()
                        .anyRequest()
                        .hasAuthority("agent:invoke"))
                .oauth2ResourceServer(o -> o.authenticationManagerResolver(request -> manager))
                .addFilterBefore(new AdmissionFilter(templates, runtime), BearerTokenAuthenticationFilter.class)
                .build();
    }
}
