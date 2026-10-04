package agent

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"time"
)

// NewHTTPClient never follows redirects or environment proxies. TLS verification stays on.
func NewHTTPClient(timeout time.Duration) *http.Client {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.Proxy = nil
	transport.MaxConnsPerHost = 8
	return &http.Client{Timeout: timeout, Transport: transport,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
}

func readLimited(reader io.Reader, limit int64) ([]byte, error) {
	data, err := io.ReadAll(io.LimitReader(reader, limit+1))
	if err != nil {
		return nil, err
	}
	if int64(len(data)) > limit {
		return nil, errors.New("response exceeds size limit")
	}
	return data, nil
}

func decodeStrict(data []byte, output any) error {
	d := json.NewDecoder(bytes.NewReader(data))
	d.DisallowUnknownFields()
	if err := d.Decode(output); err != nil {
		return err
	}
	if err := d.Decode(new(any)); err != io.EOF {
		return errors.New("expected one JSON value")
	}
	return nil
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}
