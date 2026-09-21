FROM golang:1.25-bookworm AS build

WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . ./
RUN CGO_ENABLED=0 go build -trimpath -o /out/source .

FROM debian:bookworm-slim
ARG SOURCE_REVISION=unknown
LABEL org.opencontainers.image.revision=$SOURCE_REVISION
COPY --from=build /out/source /usr/local/bin/source
ENTRYPOINT ["/usr/local/bin/source"]
