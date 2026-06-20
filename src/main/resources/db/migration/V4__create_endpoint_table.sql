CREATE TABLE endpoint (
    id          BIGSERIAL PRIMARY KEY,
    method_id   BIGINT NOT NULL REFERENCES java_method(id) ON DELETE CASCADE,
    http_method VARCHAR(10) NOT NULL,
    path        VARCHAR(500) NOT NULL,
    consumes    VARCHAR(255),
    produces    VARCHAR(255)
);

CREATE INDEX idx_endpoint_method ON endpoint(method_id);
