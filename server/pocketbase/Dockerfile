# PocketBase for Cyclocomp accounts. The release is pinned and its SHA-256 is checked, so what runs
# on the server is exactly the published build. To update: change PB_VERSION and both checksums
# (from the release's checksums.txt), rebuild, and run the smoke test again.
FROM alpine:3.20

ARG PB_VERSION=0.40.6
ARG SHA256_AMD64=0e1f609158ed945f809f8a02ae012aa0427129c66ebb63c1aee76f2c0309373f
ARG SHA256_ARM64=d22c66ceb67ca92b7d767f36d826e848a276bcfa3c197ead20bf87c42208984f

RUN apk add --no-cache ca-certificates curl unzip \
 && case "$(uname -m)" in \
      x86_64)  ARCH=amd64; SUM="$SHA256_AMD64" ;; \
      aarch64) ARCH=arm64; SUM="$SHA256_ARM64" ;; \
      *) echo "unsupported CPU: $(uname -m)"; exit 1 ;; \
    esac \
 && curl -fsSL -o /tmp/pb.zip "https://github.com/pocketbase/pocketbase/releases/download/v${PB_VERSION}/pocketbase_${PB_VERSION}_linux_${ARCH}.zip" \
 && echo "${SUM}  /tmp/pb.zip" | sha256sum -c - \
 && unzip /tmp/pb.zip -d /pb \
 && rm /tmp/pb.zip \
 && apk del curl unzip

# Run as an unprivileged user; data and migrations live in mounted volumes.
RUN adduser -D -u 10001 pb \
 && mkdir -p /pb/pb_data /pb/pb_migrations \
 && chown -R pb /pb
USER pb
WORKDIR /pb
EXPOSE 8090

CMD ["/pb/pocketbase", "serve", "--http=0.0.0.0:8090"]
