-- Run this once in the Supabase SQL editor (project: my budget) to enable notes sync

-- Notes (פתקים)
create table if not exists public.rem_notes (
  id bigint primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  title text not null default '',
  html text not null default '',
  plain text not null default '',
  color text not null default '',
  pinned boolean not null default false,
  category_id bigint not null default 0,
  created_at bigint not null default 0,
  updated_at bigint not null default 0,
  deleted_at bigint not null default 0,
  deleted boolean not null default false
);

alter table public.rem_notes enable row level security;

drop policy if exists "rem own notes" on public.rem_notes;
create policy "rem own notes" on public.rem_notes for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

grant select, insert, update, delete on public.rem_notes to authenticated, service_role;
