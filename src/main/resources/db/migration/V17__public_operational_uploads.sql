-- Only new public operational uploads. No historical content or user photos are copied.
CREATE TABLE public_operational_upload (
    id varchar(32) NOT NULL PRIMARY KEY,
    filename varchar(40) NOT NULL UNIQUE,
    url varchar(512) NOT NULL,
    mime varchar(32) NOT NULL,
    width int NOT NULL,
    height int NOT NULL,
    size_bytes bigint NOT NULL,
    sha256 char(64) NOT NULL,
    actor_id bigint NOT NULL,
    status varchar(16) NOT NULL,
    verification_expires_at timestamp NULL,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT public_upload_actor_fk FOREIGN KEY (actor_id) REFERENCES sys_user(user_id)
);
CREATE INDEX public_upload_status_created ON public_operational_upload(status, created_at);
