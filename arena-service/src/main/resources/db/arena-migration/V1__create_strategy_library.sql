CREATE TABLE snake_strategies (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    name VARCHAR(40) NOT NULL,
    description TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    error VARCHAR(255) NULL,
    active_version INT NULL,
    security_review MEDIUMTEXT NULL,
    strategy_intent MEDIUMTEXT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT ck_snake_status CHECK (status IN ('GENERATING', 'READY', 'FAILED')),
    CONSTRAINT ck_snake_ready_version CHECK ((status = 'READY' AND active_version IS NOT NULL) OR (status <> 'READY' AND active_version IS NULL))
);
CREATE INDEX idx_snake_owner_created ON snake_strategies(owner_id, created_at);
CREATE INDEX idx_snake_status ON snake_strategies(status);

CREATE TABLE snake_strategy_versions (
    strategy_id CHAR(36) NOT NULL,
    version_no INT NOT NULL,
    source_code MEDIUMTEXT NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    validation_result MEDIUMTEXT NOT NULL,
    generation_model VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (strategy_id, version_no),
    CONSTRAINT fk_snake_version FOREIGN KEY (strategy_id) REFERENCES snake_strategies(id)
);

CREATE TABLE snake_generation_diagnostics (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    strategy_id CHAR(36) NOT NULL,
    attempt_no INT NOT NULL,
    stage VARCHAR(40) NOT NULL,
    code VARCHAR(80) NOT NULL,
    detail VARCHAR(500) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT fk_snake_diagnostic FOREIGN KEY (strategy_id) REFERENCES snake_strategies(id)
);
CREATE INDEX idx_snake_diagnostic ON snake_generation_diagnostics(strategy_id, id);

-- Serialize admission/count checks across database connections.
CREATE TABLE snake_creation_guard (id INT NOT NULL PRIMARY KEY);
INSERT INTO snake_creation_guard(id) VALUES (1);
