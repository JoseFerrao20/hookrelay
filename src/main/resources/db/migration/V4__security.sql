ALTER TABLE endpoints ALTER COLUMN secret TYPE VARCHAR(255);
ALTER TABLE endpoints ADD COLUMN previous_secret VARCHAR(255);
ALTER TABLE endpoints ADD COLUMN previous_secret_expires_at TIMESTAMPTZ;
