ARG BUILD_IMAGE=debian:bookworm-slim
FROM ${BUILD_IMAGE} AS build

ARG LLAMA_CPP_COMMIT=5266f24da75dc449bd56cbed7addb9c8e4a6a73e
RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential ca-certificates cmake git \
    && rm -rf /var/lib/apt/lists/*
RUN git clone https://github.com/ggml-org/llama.cpp.git /src \
    && cd /src \
    && git checkout "${LLAMA_CPP_COMMIT}" \
    && test "$(git rev-parse HEAD)" = "${LLAMA_CPP_COMMIT}"
RUN cmake -S /src -B /src/build \
      -DCMAKE_BUILD_TYPE=Release \
      -DBUILD_SHARED_LIBS=OFF \
      -DLLAMA_BUILD_SERVER=ON \
      -DLLAMA_BUILD_TESTS=OFF \
      -DLLAMA_BUILD_EXAMPLES=OFF \
      -DLLAMA_CURL=OFF \
      -DGGML_NATIVE=OFF \
      -DGGML_CPU_ARM_ARCH=armv8-a \
    && cmake --build /src/build --config Release --target llama-server -j2

FROM debian:bookworm-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl libgomp1 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/build/bin/llama-server /usr/local/bin/llama-server
USER 65532:65532
ENTRYPOINT ["/usr/local/bin/llama-server"]
