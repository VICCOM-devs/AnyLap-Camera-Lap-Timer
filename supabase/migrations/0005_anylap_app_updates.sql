-- AnyLap auto-update gate (VICCOM rule: every product has an update path from its first build).
-- One row per platform + channel. The app reads it on launch and resume (anon, read-only):
--   build < min_version_code, or force and a newer build exists  -> blocking "Update required" screen
--   build < latest_version_code                                  -> dismissible nudge (once per version)
-- Offline / missing row -> never blocks. Only the service role writes (dashboard / SQL).
-- Test channel: the newest build comes from GitHub "test-latest"; latest_version_code there is ignored.
create table if not exists public.anylap_app_updates (
  platform            text    not null check (platform in ('android', 'ios')),
  channel             text    not null check (channel in ('test', 'play', 'appstore')),
  min_version_code    integer not null default 0,
  latest_version_code integer not null default 0,
  force               boolean not null default false,
  message_sv          text,
  message_en          text,
  store_url           text,
  rollout_percent     integer not null default 100 check (rollout_percent between 0 and 100),
  updated_at          timestamptz not null default now(),
  primary key (platform, channel)
);

alter table public.anylap_app_updates enable row level security;

drop policy if exists "app updates: anyone reads" on public.anylap_app_updates;
create policy "app updates: anyone reads" on public.anylap_app_updates
  for select to anon, authenticated using (true);

grant select on public.anylap_app_updates to anon, authenticated;

insert into public.anylap_app_updates (platform, channel, store_url) values
  ('android', 'test', 'https://github.com/VICCOM-devs/AnyLap-Camera-Lap-Timer/releases/tag/test-latest'),
  ('android', 'play', 'https://play.google.com/store/apps/details?id=se.viccom.anylap'),
  ('ios', 'appstore', null)
on conflict (platform, channel) do nothing;
