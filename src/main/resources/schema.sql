-- 화장실
CREATE TABLE IF NOT EXISTS toilet (
    id           BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conts_name   VARCHAR(255),
    addr_new     VARCHAR(255),
    addr_old     VARCHAR(255),
    coord_x      DOUBLE,            -- 경도(x)
    coord_y      DOUBLE,            -- 위도(y)
    value04      VARCHAR(64),       -- 남/여/장애/기타
    value05      VARCHAR(64),
    external_id  VARCHAR(64),       -- 업서트 자연키(자연키 해시/고유값 저장)

    UNIQUE KEY ux_toilet_external_id (external_id),
    KEY idx_toilet_coord (coord_y, coord_x)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci;

-- 리뷰
CREATE TABLE IF NOT EXISTS review (
    id          BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    toilet_id   BIGINT NOT NULL,
    rating      INT    NOT NULL,
    comment     VARCHAR(1000),
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_review_rating CHECK (rating BETWEEN 1 AND 5),
    KEY idx_review_toilet (toilet_id),
    KEY idx_review_created_at (created_at),
    CONSTRAINT fk_review_toilet
      FOREIGN KEY (toilet_id) REFERENCES toilet(id)
      ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci;