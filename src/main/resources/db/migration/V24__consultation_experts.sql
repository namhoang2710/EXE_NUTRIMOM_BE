-- Hồ sơ chuyên gia (1-1 với app.users, tài khoản mang role EXPERT do admin tạo).
CREATE TABLE app.expert_profiles (
    user_id VARCHAR(36) NOT NULL,
    full_name VARCHAR(100) NOT NULL,
    specialty VARCHAR(20) NOT NULL,
    title VARCHAR(100) NULL,
    workplace VARCHAR(255) NULL,
    years_of_experience INT NOT NULL DEFAULT 0,
    bio TEXT NULL,
    avatar_key VARCHAR(200) NULL,
    avatar_url VARCHAR(500) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    average_rating DECIMAL(3,2) NOT NULL DEFAULT 0,
    rating_count INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_expert_profiles PRIMARY KEY (user_id),
    CONSTRAINT fk_expert_profiles_user FOREIGN KEY (user_id)
        REFERENCES app.users(id),
    CONSTRAINT ck_expert_profiles_specialty CHECK (
        specialty IN ('PSYCHOLOGY', 'OBSTETRICS', 'HEALTH')),
    CONSTRAINT ck_expert_profiles_status CHECK (
        status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_expert_profiles_years CHECK (years_of_experience >= 0),
    CONSTRAINT ck_expert_profiles_rating_count CHECK (rating_count >= 0)
);

CREATE INDEX ix_expert_profiles_specialty_status
    ON app.expert_profiles(specialty, status);

-- Khung giờ trống của chuyên gia. Unique (expert, ngày, giờ bắt đầu) chống trùng slot.
CREATE TABLE app.consultation_slots (
    id VARCHAR(36) NOT NULL,
    expert_user_id VARCHAR(36) NOT NULL,
    slot_date DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_consultation_slots PRIMARY KEY (id),
    CONSTRAINT fk_consultation_slots_expert FOREIGN KEY (expert_user_id)
        REFERENCES app.expert_profiles(user_id),
    CONSTRAINT ck_consultation_slots_status CHECK (status IN ('OPEN', 'BOOKED')),
    CONSTRAINT ck_consultation_slots_time CHECK (end_time > start_time),
    CONSTRAINT ux_consultation_slots_expert_date_start
        UNIQUE (expert_user_id, slot_date, start_time)
);

CREATE INDEX ix_consultation_slots_expert_date
    ON app.consultation_slots(expert_user_id, slot_date, start_time);

-- Yêu cầu tư vấn. Owner = user_id; expert_user_id/slot_id null khi RANDOM còn ở pool.
CREATE TABLE app.consultation_requests (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    expert_user_id VARCHAR(36) NULL,
    specialty VARCHAR(20) NOT NULL,
    assignment_type VARCHAR(20) NOT NULL,
    slot_id VARCHAR(36) NULL,
    status VARCHAR(30) NOT NULL,
    note TEXT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_consultation_requests PRIMARY KEY (id),
    CONSTRAINT fk_consultation_requests_user FOREIGN KEY (user_id)
        REFERENCES app.users(id),
    CONSTRAINT fk_consultation_requests_expert FOREIGN KEY (expert_user_id)
        REFERENCES app.expert_profiles(user_id),
    CONSTRAINT fk_consultation_requests_slot FOREIGN KEY (slot_id)
        REFERENCES app.consultation_slots(id),
    CONSTRAINT ck_consultation_requests_specialty CHECK (
        specialty IN ('PSYCHOLOGY', 'OBSTETRICS', 'HEALTH')),
    CONSTRAINT ck_consultation_requests_assignment CHECK (
        assignment_type IN ('DIRECT', 'RANDOM')),
    CONSTRAINT ck_consultation_requests_status CHECK (
        status IN ('PENDING_EXPERT', 'PENDING_CONSULTATION', 'COMPLETED', 'CANCELLED'))
);

CREATE INDEX ix_consultation_requests_user_created
    ON app.consultation_requests(user_id, created_at DESC, id DESC);

-- Pool RANDOM cho chuyên gia lọc theo chuyên khoa + trạng thái.
CREATE INDEX ix_consultation_requests_pool
    ON app.consultation_requests(specialty, status, assignment_type);

-- Danh sách theo chuyên gia (list "đang chờ tư vấn" của chính họ).
CREATE INDEX ix_consultation_requests_expert
    ON app.consultation_requests(expert_user_id, status);

-- Đánh giá: mỗi yêu cầu tối đa một đánh giá.
CREATE TABLE app.consultation_reviews (
    id VARCHAR(36) NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    expert_user_id VARCHAR(36) NOT NULL,
    rating SMALLINT NOT NULL,
    comment TEXT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_consultation_reviews PRIMARY KEY (id),
    CONSTRAINT fk_consultation_reviews_request FOREIGN KEY (request_id)
        REFERENCES app.consultation_requests(id),
    CONSTRAINT fk_consultation_reviews_user FOREIGN KEY (user_id)
        REFERENCES app.users(id),
    CONSTRAINT fk_consultation_reviews_expert FOREIGN KEY (expert_user_id)
        REFERENCES app.expert_profiles(user_id),
    CONSTRAINT ck_consultation_reviews_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ux_consultation_reviews_request UNIQUE (request_id)
);

CREATE INDEX ix_consultation_reviews_expert_created
    ON app.consultation_reviews(expert_user_id, created_at DESC);

