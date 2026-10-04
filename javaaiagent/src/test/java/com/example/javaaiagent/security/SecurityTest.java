package com.example.javaaiagent.security;

import static com.example.javaaiagent.TestTimeouts.defaults;
import static org.junit.jupiter.api.Assertions.*;

import com.example.javaaiagent.AgentApplication;
import com.example.javaaiagent.config.AgentSettings;
import com.example.javaaiagent.http.BoundedHttp;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;

class SecurityTest {
    @TempDir Path directory;

    static AgentSettings settings(String keyFile) {
        return new AgentSettings(
                "local",
                "demo",
                "gpt-4.1-mini",
                "",
                URI.create("http://127.0.0.1:8080/"),
                "jwt",
                "local-demo-client-token",
                keyFile,
                "https://identity.example.com/",
                "jagent",
                URI.create("http://127.0.0.1:8081"),
                URI.create("http://127.0.0.1:8082"),
                "local-demo-status-token",
                "local-demo-runbook-token");
    }

    static KeyPair key() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    static String sign(KeyPair key, JWTClaimsSet claims) throws Exception {
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(key.getPrivate()));
        return jwt.serialize();
    }

    static JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder()
                .subject("operator-1")
                .issuer("https://identity.example.com/")
                .audience("jagent")
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 300_000))
                .claim("scope", "agent:invoke status:read")
                .claim("services", List.of("payments"));
    }

    @Test
    void signedJwtValidatesIdentityAndAuthorizationClaims() throws Exception {
        var key = key();
        Path path = directory.resolve("public.pem");
        Files.writeString(
                path,
                "-----BEGIN PUBLIC KEY-----\n"
                        + Base64.getMimeEncoder(64, new byte[] {'\n'})
                                .encodeToString(key.getPublic().getEncoded())
                        + "\n-----END PUBLIC KEY-----\n");
        var manager = SecurityConfiguration.authenticationManager(settings(path.toString()));
        var authenticated =
                manager.authenticate(
                        new BearerTokenAuthenticationToken(sign(key, claims().build())));
        var caller = (Caller) authenticated.getPrincipal();
        assertEquals("operator-1", caller.subject());
        assertTrue(caller.scopes().contains("status:read"));
        assertFalse(caller.scopes().contains("runbooks:read"));
        var unscoped =
                manager.authenticate(
                        new BearerTokenAuthenticationToken(
                                sign(key, claims().claim("scope", "").build())));
        assertTrue(unscoped.getAuthorities().isEmpty());
        for (JWTClaimsSet invalid :
                List.of(
                        claims().audience("other").build(),
                        claims().issuer("https://evil.example").build(),
                        claims().expirationTime(new Date(1)).build(),
                        claims().subject(null).build(),
                        claims().issueTime(null).build(),
                        claims().expirationTime(null).build(),
                        claims().claim("services", "payments").build())) {
            assertThrows(
                    BadCredentialsException.class,
                    () ->
                            manager.authenticate(
                                    new BearerTokenAuthenticationToken(sign(key, invalid))));
        }
        assertThrows(
                BadCredentialsException.class,
                () ->
                        manager.authenticate(
                                new BearerTokenAuthenticationToken(sign(key(), claims().build()))));
    }

    @Test
    void productionRejectsDemoAndHttp() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AgentSettings(
                                "production",
                                "demo",
                                "gpt-4.1-mini",
                                "",
                                URI.create("http://localhost/"),
                                "demo",
                                "demo",
                                "",
                                "issuer",
                                "audience",
                                URI.create("http://localhost:8081"),
                                URI.create("http://localhost:8082"),
                                "demo",
                                "demo"));
    }

    @Test
    void validJwtWithoutInvokeScopeGetsHttp403() throws Exception {
        var key = key();
        Path path = directory.resolve("http-public.pem");
        Files.writeString(
                path,
                "-----BEGIN PUBLIC KEY-----\n"
                        + Base64.getMimeEncoder(64, new byte[] {'\n'})
                                .encodeToString(key.getPublic().getEncoded())
                        + "\n-----END PUBLIC KEY-----\n");
        try (var context =
                        (org.springframework.boot.web.servlet.context
                                        .ServletWebServerApplicationContext)
                                new org.springframework.boot.builder.SpringApplicationBuilder(
                                                AgentApplication.class)
                                        .run(
                                                "--server.port=0",
                                                "--agent.environment=local",
                                                "--agent.model-mode=demo",
                                                "--agent.auth-mode=jwt",
                                                "--agent.jwt-public-key-file=" + path,
                                                "--agent.jwt-issuer=https://identity.example.com/",
                                                "--agent.jwt-audience=jagent");
                var http = BoundedHttp.client(defaults().connect())) {
            URI endpoint =
                    URI.create(
                            "http://127.0.0.1:"
                                    + context.getWebServer().getPort()
                                    + "/.well-known/agent-card.json");
            var denied =
                    http.send(
                            java.net.http.HttpRequest.newBuilder(endpoint)
                                    .header(
                                            "Authorization",
                                            "Bearer "
                                                    + sign(
                                                            key,
                                                            claims().claim("scope", "").build()))
                                    .build(),
                            java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(403, denied.statusCode());
            var allowed =
                    http.send(
                            java.net.http.HttpRequest.newBuilder(endpoint)
                                    .header(
                                            "Authorization",
                                            "Bearer " + sign(key, claims().build()))
                                    .build(),
                            java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, allowed.statusCode());
        }
    }
}
