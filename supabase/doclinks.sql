-- Linked documents for reminders and diary events (run once in the "my budget" project, SQL Editor)
alter table public.rem_tasks  add column if not exists docs text default '';
alter table public.rem_events add column if not exists docs text default '';
