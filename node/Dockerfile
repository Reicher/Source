ARG GO_IMAGE=golang:1.25-bookworm
ARG RUNTIME_IMAGE=debian:bookworm-slim
FROM ${GO_IMAGE} AS build

WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY cmd ./cmd
COPY internal ./internal
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/source-node ./cmd/source-node

FROM ${RUNTIME_IMAGE}
COPY --from=build /out/source-node /usr/local/bin/source-node
EXPOSE 8080 9090

CMD ["/usr/local/bin/source-node"]
