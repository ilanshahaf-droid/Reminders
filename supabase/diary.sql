-- Run once in the Supabase SQL editor (project: my budget) to show the phone's diary (יומן) on the web page.
-- The phone mirrors its calendar events here (read-only copy; editing stays in the app / Google Calendar).

create table if not exists public.rem_events (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  key text not null,                       -- "<event id>:<instance start ms>"
  title text not null default '',
  start_ms bigint not null,
  end_ms bigint not null,
  all_day boolean not null default false,  -- all-day events use UTC midnight bounds, end is exclusive
  color integer not null default 0,        -- ARGB int of the calendar / event colour
  location text not null default '',
  cal text not null default '',            -- calendar name
  rep boolean not null default false,      -- part of a recurring series
  primary key (user_id, key)
);

create index if not exists rem_events_start on public.rem_events (user_id, start_ms);

alter table public.rem_events enable row level security;

drop policy if exists "rem own events" on public.rem_events;
create policy "rem own events" on public.rem_events for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

-- needed explicitly for new tables (Supabase stopped auto-granting API access on Oct 30 2026)
grant select, insert, update, delete on public.rem_events to authenticated, service_role;
