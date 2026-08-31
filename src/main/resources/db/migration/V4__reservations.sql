CREATE TABLE reservations (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL,
 start_date DATE NOT NULL, end_date DATE NOT NULL, guest_count INT NOT NULL,
 memo VARCHAR(500) NOT NULL, status VARCHAR(20) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, policy_version VARCHAR(20) NOT NULL,
 request_key VARCHAR(100) NOT NULL, request_hash VARCHAR(64) NOT NULL,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 UNIQUE(user_id,request_key), FOREIGN KEY(user_id) REFERENCES users(id),
 CHECK(start_date <= end_date), CHECK(guest_count > 0)
);
CREATE INDEX ix_reservation_user ON reservations(user_id,status,start_date);
CREATE INDEX ix_reservation_calendar ON reservations(status,start_date);
CREATE TABLE calendar_blocks (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, start_date DATE NOT NULL, end_date DATE NOT NULL,
 reason VARCHAR(500) NOT NULL, status VARCHAR(20) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 created_by BIGINT NOT NULL, created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(created_by) REFERENCES users(id), CHECK(start_date <= end_date)
);
CREATE TABLE calendar_occupancy (
 use_date DATE PRIMARY KEY, reservation_id BIGINT, block_id BIGINT,
 FOREIGN KEY(reservation_id) REFERENCES reservations(id), FOREIGN KEY(block_id) REFERENCES calendar_blocks(id),
 CHECK((reservation_id IS NOT NULL AND block_id IS NULL) OR (reservation_id IS NULL AND block_id IS NOT NULL))
);
CREATE TABLE reservation_history (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, reservation_id BIGINT NOT NULL, actor_id BIGINT NOT NULL,
 action VARCHAR(30) NOT NULL, reason VARCHAR(500) NOT NULL,
 before_value VARCHAR(1000), after_value VARCHAR(1000) NOT NULL, created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(reservation_id) REFERENCES reservations(id), FOREIGN KEY(actor_id) REFERENCES users(id)
);
CREATE INDEX ix_history_reservation ON reservation_history(reservation_id,created_at);
CREATE TABLE communication_tasks (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, history_id BIGINT NOT NULL UNIQUE,
 recipient_id BIGINT NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 contacted_at DATETIME(6), acknowledged_at DATETIME(6),
 FOREIGN KEY(history_id) REFERENCES reservation_history(id), FOREIGN KEY(recipient_id) REFERENCES users(id)
);
