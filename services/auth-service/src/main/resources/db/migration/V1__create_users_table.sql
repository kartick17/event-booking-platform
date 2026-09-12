CREATE TABLE users (
   id            BIGSERIAL PRIMARY KEY,
   email         VARCHAR(255) NOT NULL,
   password_hash VARCHAR(60)  NOT NULL,
   full_name     VARCHAR(120) NOT NULL,
   enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
   created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
   updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
   CONSTRAINT uk_users_email UNIQUE (email)
);

CREATE TABLE user_roles (
    user_id BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role    VARCHAR(32) NOT NULL,
    PRIMARY KEY (user_id, role)
);