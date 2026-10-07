-- One Postgres instance, one database per service (same layout as Cloud SQL in production).
CREATE DATABASE agentregistry;
CREATE DATABASE hoteldata;
CREATE DATABASE guestops;
\c hoteldata
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
