package agent

import (
	"errors"
	"net/http"
	"strings"
)

// MockHandler serves the same synthetic remote API schema as the Python and Java fixtures.
func MockHandler(c Config, kind string) (http.Handler, error) {
	if c.Environment != "local" {
		return nil, errors.New("synthetic services are for local development only")
	}
	if kind != "status" && kind != "runbook" {
		return nil, errors.New("mock kind must be status or runbook")
	}
	token := c.StatusToken
	if kind == "runbook" {
		token = c.RunbookToken
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !equalToken(r.Header.Get("Authorization"), "Bearer "+token) {
			w.Header().Set("WWW-Authenticate", "Bearer")
			writeJSON(w, 401, map[string]string{"error": "Unauthorized"})
			return
		}
		if r.Method != "GET" {
			writeJSON(w, 405, map[string]string{"error": "GET required"})
			return
		}
		service := strings.TrimPrefix(r.URL.Path, "/services/")
		if !strings.HasPrefix(r.URL.Path, "/services/") || (service != "payments" && service != "orders") {
			writeJSON(w, 404, map[string]string{"error": "Unknown service"})
			return
		}
		result := map[string]any{"service": service, "source": "demo-" + kind + ":" + service}
		if kind == "status" {
			result["status"], result["summary"] = "healthy", "Synthetic fixture: all checks passing"
			if service == "payments" {
				result["status"], result["summary"] = "degraded", "Synthetic fixture: elevated gateway latency"
			}
		} else {
			result["steps"] = []string{"Review latency and error dashboards.", "Check recent deployments and upstream provider status.",
				"Escalate to the on-call engineer before making changes."}
		}
		writeJSON(w, 200, result)
	}), nil
}
