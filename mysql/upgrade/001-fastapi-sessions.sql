-- Apply ONCE to an existing MySQL database migrated through Spring Flyway V5.
CREATE TABLE app_sessions (
	token_hash VARCHAR(64) NOT NULL,
	csrf VARCHAR(64) NOT NULL,
	user_id BIGINT,
	auth_version BIGINT,
	authenticated_at DATETIME(6),
	expires_at DATETIME(6) NOT NULL,
	PRIMARY KEY (token_hash),
	FOREIGN KEY(user_id) REFERENCES users (id)
);

CREATE INDEX ix_session_expiry ON app_sessions (expires_at);
