-- Run once in the Supabase SQL editor (project: my budget) to sync documents (Word / PDF) between devices.
-- Files are stored as base64 text, so keep them under ~6 MB each.

create table if not exists public.rem_docs (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  name text not null,
  size integer not null default 0,
  updated_at bigint not null default 0,
  deleted boolean not null default false,
  data text not null default '',
  primary key (user_id, name)
);

alter table public.rem_docs enable row level security;

drop policy if exists "rem own docs" on public.rem_docs;
create policy "rem own docs" on public.rem_docs for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

-- needed explicitly for new tables (Supabase stopped auto-granting API access on Oct 30 2026)
grant select, insert, update, delete on public.rem_docs to authenticated, service_role;
