package agent

import (
	"crypto/rsa"
	"crypto/sha256"
	"crypto/subtle"
	"errors"
	"os"
	"strings"

	"github.com/golang-jwt/jwt/v5"
)

// Caller is created only after transport authentication, never from model arguments.
type Caller struct {
	Subject          string
	Scopes, Services map[string]bool
}

type accessClaims struct {
	jwt.RegisteredClaims
	Scope    string   `json:"scope"`
	Services []string `json:"services"`
}

func (c accessClaims) Validate() error {
	if c.Subject == "" || c.IssuedAt == nil {
		return errors.New("required identity claims missing")
	}
	return nil
}

type Authenticator struct {
	config Config
	key    *rsa.PublicKey
}

func NewAuthenticator(c Config) (*Authenticator, error) {
	a := &Authenticator{config: c}
	if c.AuthMode == "jwt" {
		data, err := os.ReadFile(c.PublicKeyFile)
		if err != nil {
			return nil, errors.New("cannot read trusted JWT public key")
		}
		a.key, err = jwt.ParseRSAPublicKeyFromPEM(data)
		if err != nil || a.key.N.BitLen() < 2048 {
			return nil, errors.New("JWT public key must be RSA with at least 2048 bits")
		}
	}
	return a, nil
}

func equalToken(a, b string) bool {
	x, y := sha256.Sum256([]byte(a)), sha256.Sum256([]byte(b))
	return subtle.ConstantTimeCompare(x[:], y[:]) == 1
}

func (a *Authenticator) Authenticate(header string) (Caller, error) {
	parts := strings.Fields(header)
	if len(parts) != 2 || !strings.EqualFold(parts[0], "bearer") {
		return Caller{}, errors.New("invalid bearer token")
	}
	if a.config.AuthMode == "demo" {
		if !equalToken(parts[1], a.config.DemoToken) {
			return Caller{}, errors.New("invalid bearer token")
		}
		return Caller{"local-demo", grants([]string{"agent:invoke", "status:read", "runbooks:read"}), grants([]string{"payments", "orders"})}, nil
	}
	claims := new(accessClaims)
	_, err := jwt.ParseWithClaims(parts[1], claims, func(token *jwt.Token) (any, error) { return a.key, nil },
		jwt.WithValidMethods([]string{"RS256"}), jwt.WithIssuer(a.config.Issuer),
		jwt.WithAudience(a.config.Audience), jwt.WithExpirationRequired(), jwt.WithIssuedAt())
	if err != nil {
		return Caller{}, errors.New("invalid bearer token")
	}
	return Caller{claims.Subject, grants(strings.Fields(claims.Scope)), grants(claims.Services)}, nil
}

func grants(values []string) map[string]bool {
	result := make(map[string]bool, len(values))
	for _, value := range values {
		result[value] = true
	}
	return result
}
