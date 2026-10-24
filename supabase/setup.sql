-- Reminders: tables, row-level security and explicit grants (required for new public tables from Oct 30 2026)

create table if not exists public.rem_categories (
  id bigint primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  name text not null,
  color bigint not null
);

create table if not exists public.rem_tasks (
  id bigint primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  title text not null,
  notes text not null default '',
  category_id bigint not null default 0,
  trigger_at bigint not null default 0,
  repeat_mode text not null default 'NONE',
  days int not null default 0,
  times text not null default '',
  slots text not null default '',
  sound text not null default '',
  vibration text not null default 'STRONG',
  done boolean not null default false,
  fired boolean not null default false,
  snooze_until bigint not null default 0
);

alter table public.rem_categories enable row level security;
alter table public.rem_tasks enable row level security;

drop policy if exists "rem own categories" on public.rem_categories;
create policy "rem own categories" on public.rem_categories for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

drop policy if exists "rem own tasks" on public.rem_tasks;
create policy "rem own tasks" on public.rem_tasks for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

grant select, insert, update, delete on public.rem_categories to authenticated, service_role;
grant select, insert, update, delete on public.rem_tasks to authenticated, service_role;
