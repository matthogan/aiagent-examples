FROM golang:1.26.5-bookworm AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY cmd ./cmd
COPY internal ./internal
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/goaiagent ./cmd/goaiagent

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/goaiagent /goaiagent
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8090
USER 65532:65532
ENTRYPOINT ["/goaiagent"]
