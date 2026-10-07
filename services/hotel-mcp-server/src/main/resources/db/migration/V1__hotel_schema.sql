CREATE TABLE guests (
    guest_id        VARCHAR(20) PRIMARY KEY,
    first_name      VARCHAR(80)  NOT NULL,
    last_name       VARCHAR(80)  NOT NULL,
    email           VARCHAR(200),
    phone           VARCHAR(40),
    loyalty_number  VARCHAR(20),
    loyalty_tier    VARCHAR(20)  NOT NULL DEFAULT 'MEMBER',   -- MEMBER | SILVER | GOLD | PLATINUM
    lifetime_nights INT          NOT NULL DEFAULT 0,
    preferences     TEXT,
    notes           TEXT
);

CREATE TABLE rooms (
    room_number  VARCHAR(10) PRIMARY KEY,
    floor        INT          NOT NULL,
    room_type    VARCHAR(10)  NOT NULL,            -- KING | QQ | JRS | EXS
    type_name    VARCHAR(60)  NOT NULL,
    accessible   BOOLEAN      NOT NULL DEFAULT FALSE,
    view         VARCHAR(40),
    status       VARCHAR(20)  NOT NULL,            -- VACANT_CLEAN | VACANT_DIRTY | OCCUPIED | OUT_OF_ORDER
    features     TEXT
);

CREATE TABLE reservations (
    confirmation_number VARCHAR(20) PRIMARY KEY,
    guest_id            VARCHAR(20)  NOT NULL REFERENCES guests (guest_id),
    room_number         VARCHAR(10)  REFERENCES rooms (room_number),
    room_type           VARCHAR(10)  NOT NULL,
    check_in            DATE         NOT NULL,
    check_out           DATE         NOT NULL,
    checkout_time       TIME         NOT NULL DEFAULT '12:00',
    status              VARCHAR(20)  NOT NULL,     -- RESERVED | IN_HOUSE | CHECKED_OUT | CANCELLED
    nightly_rate        NUMERIC(10, 2) NOT NULL,
    rate_plan           VARCHAR(40)  NOT NULL,
    adults              INT          NOT NULL DEFAULT 1,
    special_requests    TEXT,
    eta                 VARCHAR(20)
);

CREATE TABLE housekeeping_tasks (
    task_id      BIGSERIAL PRIMARY KEY,
    room_number  VARCHAR(10) NOT NULL REFERENCES rooms (room_number),
    task_type    VARCHAR(30) NOT NULL,             -- DEPARTURE_CLEAN | STAYOVER | RUSH_CLEAN | TURNDOWN | AMENITY
    status       VARCHAR(20) NOT NULL,             -- QUEUED | IN_PROGRESS | DONE | INSPECTED
    priority     VARCHAR(10) NOT NULL DEFAULT 'NORMAL',
    assigned_to  VARCHAR(80),
    eta_minutes  INT,
    notes        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE maintenance_tickets (
    ticket_id    BIGSERIAL PRIMARY KEY,
    room_number  VARCHAR(10) NOT NULL REFERENCES rooms (room_number),
    category     VARCHAR(30) NOT NULL,             -- HVAC | PLUMBING | ELECTRICAL | NOISE | FURNITURE | OTHER
    issue        TEXT        NOT NULL,
    priority     VARCHAR(10) NOT NULL,             -- LOW | NORMAL | HIGH | URGENT
    status       VARCHAR(20) NOT NULL,             -- OPEN | IN_PROGRESS | RESOLVED
    assigned_to  VARCHAR(80),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE folio_charges (
    charge_id           BIGSERIAL PRIMARY KEY,
    confirmation_number VARCHAR(20)    NOT NULL REFERENCES reservations (confirmation_number),
    charge_date         DATE           NOT NULL,
    category            VARCHAR(30)    NOT NULL,   -- ROOM | TAX | DINING | MINIBAR | PARKING | SPA | CREDIT
    description         VARCHAR(200)   NOT NULL,
    amount              NUMERIC(10, 2) NOT NULL,
    posted_by           VARCHAR(80)    NOT NULL DEFAULT 'system'
);

CREATE TABLE operation_log (
    id           BIGSERIAL PRIMARY KEY,
    operation    VARCHAR(60)  NOT NULL,
    target       VARCHAR(60)  NOT NULL,
    details      TEXT,
    performed_by VARCHAR(80)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
