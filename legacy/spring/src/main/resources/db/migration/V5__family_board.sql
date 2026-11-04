CREATE TABLE board_posts (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 title VARCHAR(100) NOT NULL, body VARCHAR(5000) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_board_user FOREIGN KEY(user_id) REFERENCES users(id)
);
CREATE INDEX ix_board_user ON board_posts(user_id);
