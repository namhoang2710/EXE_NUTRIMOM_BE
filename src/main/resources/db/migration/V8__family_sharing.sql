CREATE TABLE app.family_groups (
    id VARCHAR(36) NOT NULL,
    pregnancy_id VARCHAR(36) NOT NULL,
    owner_user_id VARCHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_family_groups PRIMARY KEY (id),
    CONSTRAINT fk_family_groups_pregnancy FOREIGN KEY (pregnancy_id)
        REFERENCES app.pregnancies(id),
    CONSTRAINT fk_family_groups_owner FOREIGN KEY (owner_user_id)
        REFERENCES app.users(id),
    CONSTRAINT ck_family_groups_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE UNIQUE INDEX ux_family_groups_pregnancy_active
    ON app.family_groups(pregnancy_id)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_family_groups_owner_status
    ON app.family_groups(owner_user_id, status);

CREATE TABLE app.family_members (
    id VARCHAR(36) NOT NULL,
    family_group_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    relationship VARCHAR(30) NOT NULL,
    membership_role VARCHAR(30) NOT NULL,
    scopes TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_family_members PRIMARY KEY (id),
    CONSTRAINT fk_family_members_group FOREIGN KEY (family_group_id)
        REFERENCES app.family_groups(id),
    CONSTRAINT fk_family_members_user FOREIGN KEY (user_id)
        REFERENCES app.users(id),
    CONSTRAINT ck_family_members_relationship CHECK (
        relationship IN ('PARTNER', 'SPOUSE', 'PARENT', 'SIBLING',
                         'RELATIVE', 'FRIEND', 'OTHER')),
    CONSTRAINT ck_family_members_role CHECK (
        membership_role IN ('PARTNER', 'FAMILY_MEMBER')),
    CONSTRAINT ck_family_members_status CHECK (status IN ('ACTIVE', 'REVOKED'))
);

CREATE UNIQUE INDEX ux_family_members_group_user_active
    ON app.family_members(family_group_id, user_id)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_family_members_user_status
    ON app.family_members(user_id, status);

CREATE TABLE app.family_invitations (
    id VARCHAR(36) NOT NULL,
    family_group_id VARCHAR(36) NOT NULL,
    invited_phone VARCHAR(20) NULL,
    invited_email VARCHAR(255) NULL,
    token_hash VARCHAR(64) NOT NULL,
    relationship VARCHAR(30) NOT NULL,
    scopes TEXT NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    accepted_at TIMESTAMP WITH TIME ZONE NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_family_invitations PRIMARY KEY (id),
    CONSTRAINT fk_family_invitations_group FOREIGN KEY (family_group_id)
        REFERENCES app.family_groups(id),
    CONSTRAINT ck_family_invitations_target CHECK (
        (invited_phone IS NOT NULL AND invited_email IS NULL)
        OR (invited_phone IS NULL AND invited_email IS NOT NULL)),
    CONSTRAINT ck_family_invitations_relationship CHECK (
        relationship IN ('PARTNER', 'SPOUSE', 'PARENT', 'SIBLING',
                         'RELATIVE', 'FRIEND', 'OTHER'))
);

CREATE UNIQUE INDEX ux_family_invitations_token_hash
    ON app.family_invitations(token_hash);

CREATE INDEX ix_family_invitations_group_created
    ON app.family_invitations(family_group_id, created_at DESC);

CREATE TABLE app.family_tasks (
    id VARCHAR(36) NOT NULL,
    family_group_id VARCHAR(36) NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT NULL,
    priority VARCHAR(20) NOT NULL,
    due_at TIMESTAMP WITH TIME ZONE NULL,
    assignee_id VARCHAR(36) NULL,
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted_at TIMESTAMP WITH TIME ZONE NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_family_tasks PRIMARY KEY (id),
    CONSTRAINT fk_family_tasks_group FOREIGN KEY (family_group_id)
        REFERENCES app.family_groups(id),
    CONSTRAINT fk_family_tasks_assignee FOREIGN KEY (assignee_id)
        REFERENCES app.family_members(id),
    CONSTRAINT ck_family_tasks_priority CHECK (
        priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    CONSTRAINT ck_family_tasks_status CHECK (
        status IN ('TODO', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'))
);

CREATE INDEX ix_family_tasks_group_assignee_status
    ON app.family_tasks(family_group_id, assignee_id, status)
    WHERE deleted_at IS NULL;

