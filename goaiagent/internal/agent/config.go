// Package agent implements a small read-only A2A operations assistant.
package agent

import (
	"errors"
	"fmt"
	"net"
	"net/url"
	"os"
	"slices"
	"strconv"
	"strings"
)

type Config struct {
	Environment, ModelMode, Model, APIKey                string
	Address, Port, AgentURL                              string
	AuthMode, DemoToken, PublicKeyFile, Issuer, Audience string
	StatusURL, RunbookURL, StatusToken, RunbookToken     string
}

// LoadConfig reads environment variables. It deliberately does not load .env files.
func LoadConfig() Config {
	return Config{
		Environment: env("ENVIRONMENT", "local"), ModelMode: env("MODEL_MODE", "demo"),
		Model: env("LLM_MODEL", "gpt-4.1-mini"), APIKey: os.Getenv("OPENAI_API_KEY"),
		Address: env("SERVER_ADDRESS", "127.0.0.1"), Port: env("SERVER_PORT", "8090"),
		AgentURL: env("AGENT_URL", "http://127.0.0.1:8090/"),
		AuthMode: env("AUTH_MODE", "demo"), DemoToken: env("DEMO_TOKEN", "local-demo-client-token"),
		PublicKeyFile: os.Getenv("JWT_PUBLIC_KEY_FILE"), Issuer: env("JWT_ISSUER", "https://identity.example.com/"),
		Audience:  env("JWT_AUDIENCE", "jagent"),
		StatusURL: env("STATUS_URL", "http://127.0.0.1:8091"), RunbookURL: env("RUNBOOK_URL", "http://127.0.0.1:8092"),
		StatusToken: env("STATUS_TOKEN", "local-demo-status-token"), RunbookToken: env("RUNBOOK_TOKEN", "local-demo-runbook-token"),
	}
}

func env(name, fallback string) string {
	if value, ok := os.LookupEnv(name); ok {
		return value
	}
	return fallback
}

func (c Config) String() string        { return "Config[redacted]" }
func (c Config) GoString() string      { return c.String() }
func (c Config) ListenAddress() string { return net.JoinHostPort(c.Address, c.Port) }

func (c Config) Validate() error {
	if !slices.Contains([]string{"local", "production"}, c.Environment) ||
		!slices.Contains([]string{"demo", "openai"}, c.ModelMode) ||
		!slices.Contains([]string{"demo", "jwt"}, c.AuthMode) {
		return errors.New("invalid environment, model mode or auth mode")
	}
	port, err := strconv.Atoi(c.Port)
	if err != nil || port < 1 || port > 65535 {
		return errors.New("SERVER_PORT must be 1–65535")
	}
	for _, address := range []string{c.AgentURL, c.StatusURL, c.RunbookURL} {
		if err := validateURL(address, c.Environment == "production"); err != nil {
			return err
		}
	}
	if c.AuthMode == "jwt" && (c.PublicKeyFile == "" || c.Issuer == "" || c.Audience == "") {
		return errors.New("JWT authentication requires a trusted public key file, issuer and audience")
	}
	if c.AuthMode == "demo" && c.DemoToken == "" {
		return errors.New("DEMO_TOKEN cannot be empty")
	}
	if c.ModelMode == "openai" && (c.APIKey == "" || c.Model == "") {
		return errors.New("real LLM mode requires OPENAI_API_KEY and LLM_MODEL")
	}
	if c.StatusToken == "" || c.RunbookToken == "" {
		return errors.New("tool credentials cannot be empty")
	}
	if c.Environment == "production" {
		if c.AuthMode != "jwt" || c.ModelMode != "openai" {
			return errors.New("production requires JWT authentication and a real LLM")
		}
		for _, token := range []string{c.StatusToken, c.RunbookToken} {
			if len(token) < 32 || strings.HasPrefix(token, "local-demo-") {
				return errors.New("production requires strong tool credentials")
			}
		}
		if c.StatusToken == c.RunbookToken {
			return errors.New("tool credentials must be distinct")
		}
	}
	return nil
}

func validateURL(address string, https bool) error {
	u, err := url.Parse(address)
	if err != nil || u.Hostname() == "" || (u.Scheme != "http" && u.Scheme != "https") ||
		u.User != nil || u.RawQuery != "" || u.Fragment != "" || (https && u.Scheme != "https") {
		return fmt.Errorf("configured URL must use %s without credentials, query or fragment", map[bool]string{true: "HTTPS", false: "HTTP(S)"}[https])
	}
	return nil
}
