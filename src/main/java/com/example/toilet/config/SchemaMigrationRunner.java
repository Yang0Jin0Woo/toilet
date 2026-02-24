package com.example.toilet.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SchemaMigrationRunner {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void migrate() {
        ensureAppUserTable();
        ensureAppUserColumns();
        ensureReviewUserColumn();
        ensureReviewUserIndex();
        ensureReviewUserFk();
    }

    private void ensureAppUserTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS app_user (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    username VARCHAR(50) NOT NULL,
                    email VARCHAR(255) NOT NULL,
                    password_hash VARCHAR(100) NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE KEY ux_app_user_username (username),
                    UNIQUE KEY ux_app_user_email (email)
                ) ENGINE=InnoDB
                  DEFAULT CHARSET = utf8mb4
                  COLLATE = utf8mb4_general_ci
                """);
    }

    private void ensureReviewUserColumn() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'review'
                  AND COLUMN_NAME = 'user_id'
                """, Integer.class);
        if (count != null && count == 0) {
            log.info("Applying schema migration: add review.user_id");
            jdbcTemplate.execute("ALTER TABLE review ADD COLUMN user_id BIGINT NULL");
        }
    }

    private void ensureAppUserColumns() {
        ensureColumnExists("app_user", "username", "ALTER TABLE app_user ADD COLUMN username VARCHAR(50) NOT NULL");
        ensureColumnExists("app_user", "email", "ALTER TABLE app_user ADD COLUMN email VARCHAR(255) NOT NULL DEFAULT ''");
        ensureColumnExists("app_user", "password_hash", "ALTER TABLE app_user ADD COLUMN password_hash VARCHAR(100) NOT NULL");
        ensureColumnExists("app_user", "created_at", "ALTER TABLE app_user ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
        ensureUniqueIndexExists("app_user", "ux_app_user_username", "ALTER TABLE app_user ADD UNIQUE INDEX ux_app_user_username (username)");
        ensureNoDuplicateEmails();
        ensureUniqueIndexExists("app_user", "ux_app_user_email", "ALTER TABLE app_user ADD UNIQUE INDEX ux_app_user_email (email)");
    }

    private void ensureNoDuplicateEmails() {
        Integer duplicateCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM (
                    SELECT email
                    FROM app_user
                    GROUP BY email
                    HAVING COUNT(*) > 1
                ) duplicated
                """, Integer.class);
        if (duplicateCount != null && duplicateCount > 0) {
            throw new IllegalStateException(
                    "Cannot apply unique index ux_app_user_email: duplicated email rows found in app_user. " +
                    "Resolve duplicate emails first.");
        }
    }

    private void ensureColumnExists(String tableName, String columnName, String ddl) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """, Integer.class, tableName, columnName);
        if (count != null && count == 0) {
            log.info("Applying schema migration: add {}.{}", tableName, columnName);
            jdbcTemplate.execute(ddl);
        }
    }

    private void ensureUniqueIndexExists(String tableName, String indexName, String ddl) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND INDEX_NAME = ?
                """, Integer.class, tableName, indexName);
        if (count != null && count == 0) {
            log.info("Applying schema migration: add index {} on {}", indexName, tableName);
            jdbcTemplate.execute(ddl);
        }
    }

    private void ensureReviewUserIndex() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'review'
                  AND INDEX_NAME = 'idx_review_user'
                """, Integer.class);
        if (count != null && count == 0) {
            log.info("Applying schema migration: add idx_review_user");
            jdbcTemplate.execute("ALTER TABLE review ADD INDEX idx_review_user (user_id)");
        }
    }

    private void ensureReviewUserFk() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.TABLE_CONSTRAINTS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'review'
                  AND CONSTRAINT_NAME = 'fk_review_user'
                  AND CONSTRAINT_TYPE = 'FOREIGN KEY'
                """, Integer.class);
        if (count != null && count == 0) {
            log.info("Applying schema migration: add fk_review_user");
            jdbcTemplate.execute("""
                    ALTER TABLE review
                    ADD CONSTRAINT fk_review_user
                    FOREIGN KEY (user_id) REFERENCES app_user(id)
                    ON DELETE CASCADE
                    """);
        }
    }
}
