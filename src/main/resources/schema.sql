-- ?붿옣??
CREATE TABLE IF NOT EXISTS toilet (
    id           BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conts_name   VARCHAR(255),
    addr_new     VARCHAR(255),
    addr_old     VARCHAR(255),
    coord_x      DOUBLE,             -- 寃쎈룄(x)
    coord_y      DOUBLE,             -- ?꾨룄(y)
    value04      VARCHAR(64),        -- ?????μ븷/湲고?
    value05      VARCHAR(64),
    external_id  VARCHAR(64) NOT NULL,  -- ?낆꽌???먯뿰???먯뿰???댁떆/怨좎쑀媛????
    rating_sum   BIGINT NOT NULL DEFAULT 0,
    rating_count BIGINT NOT NULL DEFAULT 0,
    version      BIGINT NOT NULL DEFAULT 0,

    UNIQUE KEY ux_toilet_external_id (external_id),
    KEY idx_toilet_coord (coord_y, coord_x)
) ENGINE=InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS app_user (
    id            BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(50) NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY ux_app_user_username (username)
) ENGINE=InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci;

-- 由щ럭
CREATE TABLE IF NOT EXISTS review (
    id          BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    toilet_id   BIGINT NOT NULL,
    user_id     BIGINT NULL,
    rating      INT    NOT NULL,
    comment     VARCHAR(1000),
    report_count INT   NOT NULL DEFAULT 0,
    blocked     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version     BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT chk_review_rating CHECK (rating BETWEEN 1 AND 5),
    KEY idx_review_toilet (toilet_id),
    KEY idx_review_user (user_id),
    KEY idx_review_created_at (created_at),
    CONSTRAINT fk_review_toilet
      FOREIGN KEY (toilet_id) REFERENCES toilet(id)
      ON DELETE CASCADE,
    CONSTRAINT fk_review_user
      FOREIGN KEY (user_id) REFERENCES app_user(id)
      ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS review_page_view (
    toilet_id  BIGINT NOT NULL PRIMARY KEY,
    view_count BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT fk_review_page_view_toilet
      FOREIGN KEY (toilet_id) REFERENCES toilet(id)
      ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci;
