-- AnyLap backend, v2 stage 1: accounts, cloud history, racers shared between phones.
-- Lives in the psst Supabase project (free-plan project limit), so every object is prefixed anylap_.
-- Nothing here touches psst tables. Clients only ever hold the publishable key.

-- ------------------------------------------------------------------ helpers

create or replace function public.anylap_touch_updated_at()
returns trigger language plpgsql set search_path = '' as $$
begin
  new.updated_at := now();
  return new;
end $$;

-- ------------------------------------------------------------------ remote config (single row)

create table public.anylap_config (
  id int primary key default 1 check (id = 1),
  -- While true every signed-in user counts as Pro on the server (closed testing).
  open_beta boolean not null default true,
  min_version_code int not null default 1,
  updated_at timestamptz not null default now()
);
insert into public.anylap_config default values;
alter table public.anylap_config enable row level security;
create policy "anylap_config readable by everyone" on public.anylap_config
  for select to anon, authenticated using (true);

-- ------------------------------------------------------------------ profiles

create table public.anylap_profiles (
  user_id uuid primary key references auth.users on delete cascade default auth.uid(),
  display_name text not null default '' check (char_length(display_name) <= 40),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create trigger anylap_profiles_touch before update on public.anylap_profiles
  for each row execute function public.anylap_touch_updated_at();
alter table public.anylap_profiles enable row level security;
create policy "own profile: read" on public.anylap_profiles for select to authenticated using (user_id = (select auth.uid()));
create policy "own profile: create" on public.anylap_profiles for insert to authenticated with check (user_id = (select auth.uid()));
create policy "own profile: update" on public.anylap_profiles for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

-- ------------------------------------------------------------------ entitlements (written only server-side)

create table public.anylap_entitlements (
  user_id uuid not null references auth.users on delete cascade,
  product text not null,
  source text not null check (source in ('play', 'appstore', 'revenuecat', 'grant')),
  expires_at timestamptz,
  created_at timestamptz not null default now(),
  primary key (user_id, product)
);
alter table public.anylap_entitlements enable row level security;
create policy "own entitlements: read" on public.anylap_entitlements for select to authenticated using (user_id = (select auth.uid()));

-- Is the caller Pro on the server? (open beta, or an active entitlement)
create or replace function public.anylap_has_pro()
returns boolean language sql stable security definer set search_path = '' as $$
  select coalesce((select c.open_beta from public.anylap_config c where c.id = 1), false)
      or exists (
        select 1 from public.anylap_entitlements e
        where e.user_id = (select auth.uid()) and (e.expires_at is null or e.expires_at > now())
      );
$$;

-- ------------------------------------------------------------------ racers

create table public.anylap_racers (
  id uuid primary key,                         -- same id as on the phone
  user_id uuid not null references auth.users on delete cascade default auth.uid(),
  sport text not null check (sport in ('RC', 'RUNNING')),
  data jsonb not null check (pg_column_size(data) < 65536),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);
create index anylap_racers_user_updated on public.anylap_racers (user_id, updated_at);
create trigger anylap_racers_touch before update on public.anylap_racers
  for each row execute function public.anylap_touch_updated_at();
alter table public.anylap_racers enable row level security;
create policy "own racers: read" on public.anylap_racers for select to authenticated using (user_id = (select auth.uid()));
create policy "own racers: create (pro)" on public.anylap_racers for insert to authenticated
  with check (user_id = (select auth.uid()) and public.anylap_has_pro());
create policy "own racers: update (pro)" on public.anylap_racers for update to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()) and public.anylap_has_pro());

-- Someone who entered a share code for a racer may time that racer on their phone.
create table public.anylap_racer_links (
  racer_id uuid not null references public.anylap_racers on delete cascade,
  user_id uuid not null references auth.users on delete cascade,
  created_at timestamptz not null default now(),
  primary key (racer_id, user_id)
);
create index anylap_racer_links_user on public.anylap_racer_links (user_id);
alter table public.anylap_racer_links enable row level security;
create policy "own links: read" on public.anylap_racer_links for select to authenticated using (user_id = (select auth.uid()));

-- Short-lived share codes (only reachable through the functions below).
create table public.anylap_racer_shares (
  code text primary key,
  racer_id uuid not null references public.anylap_racers on delete cascade,
  user_id uuid not null references auth.users on delete cascade,
  expires_at timestamptz not null
);
create index anylap_racer_shares_racer on public.anylap_racer_shares (racer_id);
create index anylap_racer_shares_user on public.anylap_racer_shares (user_id);
alter table public.anylap_racer_shares enable row level security;

-- ------------------------------------------------------------------ sessions

create table public.anylap_sessions (
  id uuid primary key,                         -- same id as on the phone
  user_id uuid not null references auth.users on delete cascade default auth.uid(),  -- who timed it
  sport text not null check (sport in ('RC', 'RUNNING')),
  started_at timestamptz not null,
  data jsonb not null check (pg_column_size(data) < 524288),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);
create index anylap_sessions_user_updated on public.anylap_sessions (user_id, updated_at);
create trigger anylap_sessions_touch before update on public.anylap_sessions
  for each row execute function public.anylap_touch_updated_at();
alter table public.anylap_sessions enable row level security;

-- Which accounts' racers took part in a session, so it shows up in their history too.
create table public.anylap_session_racers (
  session_id uuid not null references public.anylap_sessions on delete cascade,
  racer_id uuid not null references public.anylap_racers on delete cascade,
  racer_owner uuid not null references auth.users on delete cascade,
  primary key (session_id, racer_id)
);
create index anylap_session_racers_owner on public.anylap_session_racers (racer_owner);
create index anylap_session_racers_racer on public.anylap_session_racers (racer_id);
alter table public.anylap_session_racers enable row level security;

create or replace function public.anylap_owns_session(sid uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.anylap_sessions s where s.id = sid and s.user_id = (select auth.uid()));
$$;

create or replace function public.anylap_in_session(sid uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.anylap_session_racers sr where sr.session_id = sid and sr.racer_owner = (select auth.uid()));
$$;

create policy "sessions: owner or racer reads" on public.anylap_sessions for select to authenticated
  using (user_id = (select auth.uid()) or public.anylap_in_session(id));
create policy "sessions: owner creates (pro)" on public.anylap_sessions for insert to authenticated
  with check (user_id = (select auth.uid()) and public.anylap_has_pro());
create policy "sessions: owner updates (pro)" on public.anylap_sessions for update to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()) and public.anylap_has_pro());

create policy "session racers: owner or racer reads" on public.anylap_session_racers for select to authenticated
  using (racer_owner = (select auth.uid()) or public.anylap_owns_session(session_id));
create policy "session racers: session owner tags" on public.anylap_session_racers for insert to authenticated
  with check (public.anylap_owns_session(session_id));

-- racer_owner is always taken from the racer, never from the client, and only racers the
-- uploader owns or has a share link for can be tagged.
create or replace function public.anylap_session_racers_guard()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
  owner uuid;
begin
  select r.user_id into owner from public.anylap_racers r where r.id = new.racer_id;
  if owner is null then
    raise exception 'unknown racer';
  end if;
  if owner <> (select auth.uid()) and not exists (
    select 1 from public.anylap_racer_links l where l.racer_id = new.racer_id and l.user_id = (select auth.uid())
  ) then
    raise exception 'racer not shared with you';
  end if;
  new.racer_owner := owner;
  return new;
end $$;
create trigger anylap_session_racers_guard before insert on public.anylap_session_racers
  for each row execute function public.anylap_session_racers_guard();

-- ------------------------------------------------------------------ RPCs

-- Make a 6-character code (valid 24 h) another phone can enter to time this racer.
create or replace function public.anylap_share_racer(p_racer uuid)
returns text language plpgsql security definer set search_path = '' as $$
declare
  alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  c text;
begin
  if not exists (select 1 from public.anylap_racers r where r.id = p_racer and r.user_id = (select auth.uid()) and r.deleted_at is null) then
    raise exception 'not your racer';
  end if;
  delete from public.anylap_racer_shares s where s.expires_at < now();
  loop
    c := '';
    for i in 1..6 loop
      c := c || substr(alphabet, 1 + floor(random() * length(alphabet))::int, 1);
    end loop;
    exit when not exists (select 1 from public.anylap_racer_shares s where s.code = c);
  end loop;
  insert into public.anylap_racer_shares (code, racer_id, user_id, expires_at)
    values (c, p_racer, (select auth.uid()), now() + interval '24 hours');
  return c;
end $$;

-- Enter a share code: links the racer to the caller and returns it.
create or replace function public.anylap_claim_racer(p_code text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  s public.anylap_racer_shares;
  r public.anylap_racers;
  owner_name text;
begin
  select * into s from public.anylap_racer_shares x where x.code = upper(trim(p_code)) and x.expires_at > now();
  if s.code is null then
    raise exception 'code not found or expired';
  end if;
  select * into r from public.anylap_racers x where x.id = s.racer_id and x.deleted_at is null;
  if r.id is null then
    raise exception 'code not found or expired';
  end if;
  if r.user_id <> (select auth.uid()) then
    insert into public.anylap_racer_links (racer_id, user_id) values (r.id, (select auth.uid())) on conflict do nothing;
  end if;
  select p.display_name into owner_name from public.anylap_profiles p where p.user_id = r.user_id;
  return jsonb_build_object('id', r.id, 'owner', r.user_id, 'owner_name', coalesce(owner_name, ''), 'data', r.data);
end $$;

-- GDPR: everything AnyLap stores about the caller.
create or replace function public.anylap_export_my_data()
returns jsonb language sql stable security definer set search_path = '' as $$
  select jsonb_build_object(
    'profile', (select to_jsonb(p) from public.anylap_profiles p where p.user_id = (select auth.uid())),
    'entitlements', coalesce((select jsonb_agg(to_jsonb(e)) from public.anylap_entitlements e where e.user_id = (select auth.uid())), '[]'::jsonb),
    'racers', coalesce((select jsonb_agg(to_jsonb(r)) from public.anylap_racers r where r.user_id = (select auth.uid())), '[]'::jsonb),
    'sessions', coalesce((select jsonb_agg(to_jsonb(s)) from public.anylap_sessions s where s.user_id = (select auth.uid())), '[]'::jsonb),
    'racer_links', coalesce((select jsonb_agg(to_jsonb(l)) from public.anylap_racer_links l where l.user_id = (select auth.uid())), '[]'::jsonb)
  );
$$;

-- GDPR: wipe the caller's AnyLap data (the shared auth user is left alone because psst uses it too).
create or replace function public.anylap_delete_my_data()
returns void language plpgsql security definer set search_path = '' as $$
declare
  uid uuid := (select auth.uid());
begin
  if uid is null then raise exception 'not signed in'; end if;
  delete from public.anylap_session_racers where racer_owner = uid;
  delete from public.anylap_sessions where user_id = uid;
  delete from public.anylap_racer_shares where user_id = uid;
  delete from public.anylap_racer_links where user_id = uid;
  delete from public.anylap_racers where user_id = uid;
  delete from public.anylap_entitlements where user_id = uid;
  delete from public.anylap_profiles where user_id = uid;
end $$;

-- Only signed-in users may call the functions; nobody calls the trigger helpers directly.
revoke execute on function public.anylap_has_pro(), public.anylap_owns_session(uuid), public.anylap_in_session(uuid),
  public.anylap_share_racer(uuid), public.anylap_claim_racer(text), public.anylap_export_my_data(), public.anylap_delete_my_data(),
  public.anylap_session_racers_guard(), public.anylap_touch_updated_at()
  from public, anon;
grant execute on function public.anylap_has_pro(), public.anylap_owns_session(uuid), public.anylap_in_session(uuid),
  public.anylap_share_racer(uuid), public.anylap_claim_racer(text), public.anylap_export_my_data(), public.anylap_delete_my_data()
  to authenticated;
