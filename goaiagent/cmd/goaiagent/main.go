package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"example.com/goaiagent/internal/agent"
)

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stderr, nil))
	if err := run(os.Args[1:], log); err != nil {
		log.Error("command_failed", "error", err.Error())
		os.Exit(1)
	}
}

func run(args []string, log *slog.Logger) error {
	c := agent.LoadConfig()
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if len(args) > 0 && args[0] == "client" {
		question := "Why is payments degraded, and what should I check?"
		if len(args) > 1 {
			question = strings.Join(args[1:], " ")
		}
		token := os.Getenv("A2A_TOKEN")
		if token == "" && c.AuthMode == "demo" {
			token = c.DemoToken
		}
		answer, err := agent.Ask(ctx, c.AgentURL, token, question)
		if err != nil {
			return err
		}
		fmt.Println(string(answer))
		return nil
	}
	var handler http.Handler
	address := c.ListenAddress()
	if len(args) > 0 && args[0] == "mock" {
		if len(args) < 2 || len(args) > 3 {
			return errors.New("usage: goaiagent mock status|runbook [port]")
		}
		var err error
		handler, err = agent.MockHandler(c, args[1])
		if err != nil {
			return err
		}
		port := "8091"
		if args[1] == "runbook" {
			port = "8092"
		}
		if len(args) == 3 {
			port = args[2]
		}
		value, err := strconv.Atoi(port)
		if err != nil || value < 1 || value > 65535 {
			return errors.New("mock port must be 1–65535")
		}
		address = net.JoinHostPort("127.0.0.1", port)
	} else {
		if len(args) > 1 || (len(args) == 1 && args[0] != "serve") {
			return errors.New("usage: goaiagent [serve|client [question]|mock status|runbook [port]]")
		}
		if err := c.Validate(); err != nil {
			return err
		}
		model, err := agent.NewModel(ctx, c)
		if err != nil {
			return errors.New("could not initialize model")
		}
		server, err := agent.NewServer(c, model, log)
		if err != nil {
			return err
		}
		defer server.Close()
		handler = server
	}
	server := agent.HTTPServer(address, handler)
	done := make(chan error, 1)
	go func() { done <- server.ListenAndServe() }()
	log.Info("listening", "address", address)
	select {
	case err := <-done:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	case <-ctx.Done():
		shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := server.Shutdown(shutdown); err != nil {
			return server.Close()
		}
		return nil
	}
}
