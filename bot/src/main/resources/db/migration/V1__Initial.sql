CREATE TABLE bot_users (
    id bigint NOT NULL,
    lang character varying(7),
    CONSTRAINT bot_users_pkey PRIMARY KEY (id)
);

CREATE TABLE drivers (
    id bigint NOT NULL,
    full_name character varying(255) NOT NULL,
    license_series character varying(7) NOT NULL,
    license_number character varying(15) NOT NULL,
    CONSTRAINT drivers_pkey PRIMARY KEY (id)
);

CREATE TABLE fines (
    id bigint NOT NULL,
    driver_id bigint NOT NULL,
    date_time timestamp NOT NULL,
    is_active bit NOT NULL,
    CONSTRAINT fines_pkey PRIMARY KEY (id),
    CONSTRAINT fines_driver_id_fkey FOREIGN KEY (driver_id) REFERENCES drivers(id)
);

-- INSERT INTO drivers (id, full_name, license_series, license_number) VALUES (9876, 'D L', 'MAA', '1234123');
