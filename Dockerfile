# metapaste v2 — multi-stage container build (Q54).
#
# Stage 1 builds the deliverable INSIDE the image: `jolt build --closed-world`
# from the pinned jolt v0.8.20 source on top of the pinned threaded Chez
# Scheme 10.4.1. Both are pinned source builds because no jolt release ships
# a prebuilt aarch64-linux toolchain (assets: x86_64-linux, aarch64-macos,
# aarch64-android) and the spec pins Chez 10.4.1 exactly — `bin/jolt` finds
# the Chez below through JOLT_CHEZ. Stage 2 is a bare glibc runtime (debian
# bookworm, glibc 2.36 — NOT musl/Alpine) holding only the self-contained
# binary: no jolt, no Chez, no compiler on PATH.
#
# Runtime contract (verified by scripts/docker-selftest.sh):
#   - non-root user (uid 10001)
#   - run with `--read-only` — the root FS is never written to
#   - exactly one writable volume: VOLUME /data (METAPASTE_DATA_DIR=/data)
#   - EXPOSE 8080 (app) + 8081 (metrics); publish metrics deliberately only
#   - METAPASTE_BIND_HOST=0.0.0.0 in-container; the default outside
#     containers stays loopback

FROM debian:bookworm-slim AS build

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       build-essential ca-certificates curl git libncurses-dev libssl-dev libsqlite3-dev uuid-dev xxd \
    && rm -rf /var/lib/apt/lists/*

# Threaded Chez Scheme 10.4.1 — the spec's host and jolt's floor (bin/jolt
# probes for a threaded 10.x). configure: --threads (threaded runtime),
# --installprefix (layout bin/ + lib/csv10.4.1/<machine>).
ARG CHEZ_VERSION=v10.4.1
RUN git clone --depth 1 --branch "${CHEZ_VERSION}" \
        https://github.com/cisco/ChezScheme.git /tmp/chez \
    && cd /tmp/chez \
    && ./configure --threads --disable-x11 --installprefix=/usr/local \
    && make -j"$(nproc)" \
    && make install \
    && rm -rf /tmp/chez \
    && printf '(display (threaded?)) (newline)\n' | /usr/local/bin/scheme -q | grep -q '#t'

# jolt v0.8.20 from source at its release tag (version.sh must git-describe
# the tag); submodules vendored per CONTRIBUTING ("no build step" from there)
ARG JOLT_VERSION=v0.8.20
ENV JOLT_CHEZ=/usr/local/bin/scheme
RUN git clone --depth 1 --branch "${JOLT_VERSION}" --recurse-submodules \
        https://github.com/jolt-lang/jolt.git /opt/jolt \
    && /opt/jolt/bin/jolt --version | grep -q "v0.8.20"

WORKDIR /build
# deps.edn alone as its own layer: dependency fetch (4 pinned git deps +
# 2 pinned maven jars) stays cached while source changes
COPY deps.edn ./
RUN /opt/jolt/bin/jolt path > /dev/null
COPY resources ./resources
COPY src ./src
RUN /opt/jolt/bin/jolt build -m app.core --closed-world -o metapaste

FROM debian:bookworm-slim

# libtinfo6 comes in with bash; libsqlite3-0 (jolt-lang/db FFI) and libssl3
# (jolt-lang/jolt-crypto SHA-256 for API keys) are the only other native
# libraries the binary dlopens. Everything else is in libc.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libsqlite3-0 libssl3 \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home metapaste \
    && mkdir -p /data \
    && chown metapaste:metapaste /data

COPY --from=build /build/metapaste /usr/local/bin/metapaste

ENV METAPASTE_DATA_DIR=/data \
    METAPASTE_BIND_HOST=0.0.0.0 \
    METAPASTE_PORT=8080 \
    METAPASTE_METRICS_PORT=8081

EXPOSE 8080 8081
# the ONE writable path; `docker run --read-only` keeps everything else firm
VOLUME ["/data"]

USER metapaste
ENTRYPOINT ["/usr/local/bin/metapaste", "serve"]
