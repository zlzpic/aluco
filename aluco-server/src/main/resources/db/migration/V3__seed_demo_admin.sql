-- Seed the demo login (README "Default Accounts": admin / admin123).
-- Without this row a fresh database has no user at all, so POST /auth/login fails
-- and the compose demo (and the simulator's REST seeding) cannot start.
--
-- The BCrypt hash below is for the publicly documented demo password "admin123";
-- it is a placeholder credential, not a secret. Change it before any real deployment.
--
-- Written as INSERT ... SELECT ... WHERE NOT EXISTS so it is idempotent on databases
-- that were seeded manually, and portable between MySQL 8.4 and H2 (MODE=MySQL).

INSERT INTO app_user (username, password_hash)
SELECT seed.username, seed.password_hash
FROM (SELECT 'admin' AS username, '$2a$10$SJm9lD7YjrA8bPwsImXv9uurAG0AoUj8TXs9/0BhKFfUqtsU5k6HK' AS password_hash) AS seed
WHERE NOT EXISTS (SELECT 1 FROM app_user u WHERE u.username = 'admin');
