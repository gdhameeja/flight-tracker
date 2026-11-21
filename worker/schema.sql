-- Flight Tracker database (Cloudflare D1). Safe to run again.
-- npx wrangler d1 execute flight-tracker --remote --file=schema.sql

-- One row per tracked flight; `data` is the Flight JSON (src/model.ts)
create table if not exists flights (
  id text primary key,
  number text not null,
  date text not null,            -- local departure date, YYYY-MM-DD
  dep_iata text,                 -- picks the leg when one number flies several
  data text not null,
  next_check_at integer,         -- epoch ms; null = nothing left to watch
  created_at integer not null,
  updated_at integer not null,
  unique (number, date, dep_iata)
);
create index if not exists flights_due on flights (next_check_at);

-- What changed, newest last: the app's timeline and the alerts
create table if not exists events (
  id integer primary key autoincrement,
  flight_id text not null references flights (id) on delete cascade,
  at integer not null,
  kind text not null,
  title text not null,
  body text not null,
  pushed integer not null default 0
);
create index if not exists events_flight on events (flight_id, at);

-- Small key/value settings: API units left, push tokens, ...
create table if not exists meta (
  key text primary key,
  value text not null
);

-- Booking details found in email: confirmation code, seats, who's flying
create table if not exists bookings (
  flight_id text primary key references flights (id) on delete cascade,
  pnr text,
  seats text,
  passengers text,
  airline_name text,
  message_id text,
  subject text,
  updated_at integer not null
);

-- Emails already read by the Gmail import, so each is read once
create table if not exists gmail_seen (
  message_id text primary key,
  at integer not null,
  result text not null          -- "flights:2", "none", "skipped", "error: ..."
);
