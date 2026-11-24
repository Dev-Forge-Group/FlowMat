-- Keeping personal data only as long as needed (docs/operations/personal-data.md P1, P2): a withdrawn user's name, contact,
-- birth date and social links are erased once the retention period after withdrawal has passed, so the phone and birth
-- date may be empty, and the erasure is marked. Login history older than its retention goes. Added only.
ALTER TABLE users ALTER COLUMN user_tel DROP NOT NULL;
ALTER TABLE users ALTER COLUMN user_birth DROP NOT NULL;
ALTER TABLE users ADD COLUMN personal_data_erased_at timestamptz;
CREATE INDEX idx_users_withdrawn_to_erase ON users (withdrawn_at)
    WHERE user_status = 'withdrawn' AND personal_data_erased_at IS NULL;
CREATE INDEX idx_user_login_history_login_at ON user_login_history (login_at);
