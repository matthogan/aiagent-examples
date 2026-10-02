package com.example.javaaiagent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
    static AuthenticationManager authenticationManager(AgentSettings settings) throws Exception {
        NimbusJwtDecoder decoder;
        if (settings.authMode().equals("jwt")) {
            try (var key = Files.newInputStream(Path.of(settings.jwtPublicKeyFile()))) {
                decoder = NimbusJwtDecoder.withPublicKey(RsaKeyConverters.x509().convert(key))
                        .signatureAlgorithm(SignatureAlgorithm.RS256).build();
            }
            OAuth2TokenValidator<Jwt> claims = token -> {
                boolean valid = token.getExpiresAt() != null && token.getIssuedAt() != null
                        && token.getIssuedAt().isBefore(Instant.now().plusSeconds(60))
                        && token.getSubject() != null && !token.getSubject().isBlank()
                        && token.getAudience().contains(settings.jwtAudience());
                return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Required claims missing or invalid", null));
            };
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(settings.jwtIssuer()), claims));
        } else {
            decoder = null;
        }
        return authentication -> {
            String token = ((BearerTokenAuthenticationToken) authentication).getToken();
            Caller caller;
            if (decoder == null) {
                if (!constantEquals(token, settings.demoToken())) {
                    throw new BadCredentialsException("Invalid bearer token");
                }
                caller = new Caller("local-demo", Set.of("agent:invoke", "status:read", "runbooks:read"),
                        Set.of("payments", "orders"));
            } else {
                try {
                    Jwt jwt = decoder.decode(token);
                    Object scope = jwt.getClaims().getOrDefault("scope", "");
                    Object services = jwt.getClaims().getOrDefault("services", List.of());
                    if (!(scope instanceof String scopeString) || !(services instanceof List<?> grants)
                            || grants.stream().anyMatch(s -> !(s instanceof String))) {
                        throw new BadCredentialsException("Invalid authorization claims");
                    }
                    caller = new Caller(jwt.getSubject(), java.util.Arrays.stream(scopeString.split("\\s+"))
                            .filter(s -> !s.isBlank()).collect(java.util.stream.Collectors.toSet()),
                            grants.stream().map(String.class::cast).collect(java.util.stream.Collectors.toSet()));
                } catch (JwtException | IllegalArgumentException ex) {
                    throw new BadCredentialsException("Invalid bearer token");
                }
            }
            return UsernamePasswordAuthenticationToken.authenticated(caller, null,
                    caller.scopes().stream().map(SimpleGrantedAuthority::new).toList());
        };
    }

    static boolean constantEquals(String supplied, String expected) {
        return MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, AgentSettings settings) throws Exception {
        var manager = authenticationManager(settings);
        return http.csrf(csrf -> csrf.disable()) // Bearer headers only; no cookie authentication.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .authorizeHttpRequests(a -> a.requestMatchers("/healthz").permitAll()
                        .anyRequest().hasAuthority("agent:invoke"))
                .oauth2ResourceServer(o -> o.authenticationManagerResolver(request -> manager))
                .addFilterBefore(new AdmissionFilter(), BearerTokenAuthenticationFilter.class)
                .build();
    }
}
