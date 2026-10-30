-- AnyLap car-recognition training data + model updates.
--
-- Crops: with "Help improve car recognition" on (default, after a one-time notice), the app shares small
-- pictures of the finish line at a crossing together with which car it was (random racer uuids, never names)
-- and the camera facts. They train the recognition model that ships in future versions. Users can only
-- write their own rows/objects and never read any; training reads with the service role.
-- Models: the trained model is published here (public, read-only) and downloaded by the app without an
-- app update (same pattern as anylap_app_updates).

-- ---------------------------------------------------------------- crops (metadata)
create table if not exists public.anylap_crops (
  id          uuid primary key,
  user_id     uuid not null references auth.users (id) on delete cascade,
  session_id  uuid not null,
  at_ms       bigint not null,
  -- which car the race (or the human) finally counted it for; null = nobody (not a car / unknown)
  racer_id    uuid,
  -- auto | fit | verdict | card:<ReviewKind>
  kind        text not null,
  -- object path in the bucket anylap-crops: <user_id>/<session_id>/<id>.jpg
  path        text not null,
  meta        jsonb not null default '{}'::jsonb,
  app_build   integer not null default 0,
  created_at  timestamptz not null default now()
);

create index if not exists anylap_crops_user_idx on public.anylap_crops (user_id, created_at);
create index if not exists anylap_crops_session_idx on public.anylap_crops (session_id, at_ms);

alter table public.anylap_crops enable row level security;

drop policy if exists "crops: insert own" on public.anylap_crops;
create policy "crops: insert own" on public.anylap_crops
  for insert to authenticated with check (user_id = (select auth.uid()));

-- the app upserts (a retried upload must not fail on the key): updating own rows only
drop policy if exists "crops: update own" on public.anylap_crops;
create policy "crops: update own" on public.anylap_crops
  for update to authenticated using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

-- no select policy on purpose: nobody reads crops through the API but the service role
grant insert, update on public.anylap_crops to authenticated;

-- ---------------------------------------------------------------- crops (pictures)
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('anylap-crops', 'anylap-crops', false, 2097152, array['image/jpeg', 'image/bmp'])
on conflict (id) do nothing;

drop policy if exists "anylap crops: upload own" on storage.objects;
create policy "anylap crops: upload own" on storage.objects
  for insert to authenticated
  with check (bucket_id = 'anylap-crops' and (storage.foldername(name))[1] = (select auth.uid())::text);

drop policy if exists "anylap crops: replace own" on storage.objects;
create policy "anylap crops: replace own" on storage.objects
  for update to authenticated
  using (bucket_id = 'anylap-crops' and (storage.foldername(name))[1] = (select auth.uid())::text)
  with check (bucket_id = 'anylap-crops' and (storage.foldername(name))[1] = (select auth.uid())::text);

-- ---------------------------------------------------------------- models
create table if not exists public.anylap_models (
  name          text not null,              -- e.g. 'signature'
  platform      text not null check (platform in ('android', 'ios', 'all')),
  version       integer not null,
  url           text not null,              -- where the app downloads it (public bucket or GitHub release)
  sha256        text not null,
  bytes         integer not null default 0,
  min_app_build integer not null default 0, -- the app must be at least this build to use it
  notes         text,
  created_at    timestamptz not null default now(),
  primary key (name, platform, version)
);

alter table public.anylap_models enable row level security;

drop policy if exists "models: anyone reads" on public.anylap_models;
create policy "models: anyone reads" on public.anylap_models
  for select to anon, authenticated using (true);

grant select on public.anylap_models to anon, authenticated;

-- ---------------------------------------------------------------- GDPR
create or replace function public.anylap_export_my_data()
returns jsonb language sql stable security definer set search_path = '' as $$
  select jsonb_build_object(
    'profile', (select to_jsonb(p) from public.anylap_profiles p where p.user_id = (select auth.uid())),
    'entitlements', coalesce((select jsonb_agg(to_jsonb(e)) from public.anylap_entitlements e where e.user_id = (select auth.uid())), '[]'::jsonb),
    'racers', coalesce((select jsonb_agg(to_jsonb(r)) from public.anylap_racers r where r.user_id = (select auth.uid())), '[]'::jsonb),
    'sessions', coalesce((select jsonb_agg(to_jsonb(s)) from public.anylap_sessions s where s.user_id = (select auth.uid())), '[]'::jsonb),
    'racer_links', coalesce((select jsonb_agg(to_jsonb(l)) from public.anylap_racer_links l where l.user_id = (select auth.uid())), '[]'::jsonb),
    'shared_crops', coalesce((select jsonb_agg(jsonb_build_object('id', c.id, 'session_id', c.session_id, 'at_ms', c.at_ms, 'racer_id', c.racer_id, 'kind', c.kind, 'created_at', c.created_at))
                              from public.anylap_crops c where c.user_id = (select auth.uid())), '[]'::jsonb)
  );
$$;

create or replace function public.anylap_delete_my_data()
returns void language plpgsql security definer set search_path = '' as $$
declare
  uid uuid := (select auth.uid());
begin
  if uid is null then raise exception 'not signed in'; end if;
  delete from storage.objects where bucket_id = 'anylap-crops' and (storage.foldername(name))[1] = uid::text;
  delete from public.anylap_crops where user_id = uid;
  delete from public.anylap_live_viewers where user_id = uid;
  delete from public.anylap_live where user_id = uid;
  delete from public.anylap_session_racers where racer_owner = uid;
  delete from public.anylap_sessions where user_id = uid;
  delete from public.anylap_racer_shares where user_id = uid;
  delete from public.anylap_racer_links where user_id = uid;
  delete from public.anylap_racers where user_id = uid;
  delete from public.anylap_entitlements where user_id = uid;
  delete from public.anylap_profiles where user_id = uid;
end $$;
