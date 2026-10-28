-- Live sessions: the camera phone writes a small snapshot of the timing tower; any AnyLap app (or the
-- web page) joins by code / QR without signing in and reads it through anylap_live_join.
--
-- Who sees what (decided Oct 26 2026, "Product model"):
--   camera Free → 1 viewer at a time (app or web), Pro → unlimited viewers;
--   every viewer: positions, laps, lap times, gaps, results;
--   expanded stats (speeds, consistency …) only when the camera is Pro AND the viewer is Pro AND the
--   car's owner is Pro (the camera owner's cars count as theirs). A Free camera never sends them.
-- Tier: real entitlements; while anylap_config.open_beta is on, the tier the app says it has is used
-- (so Free/Pro combinations can be tested before the store exists).

create table public.anylap_live (
  code text primary key,
  user_id uuid not null references auth.users on delete cascade default auth.uid(),
  host_pro boolean not null default false,
  snapshot jsonb not null default '{}'::jsonb check (pg_column_size(snapshot) < 65536),
  updated_at timestamptz not null default now(),
  expires_at timestamptz not null default now() + interval '12 hours'
);
create index anylap_live_user on public.anylap_live (user_id);
create trigger anylap_live_touch before update on public.anylap_live
  for each row execute function public.anylap_touch_updated_at();
alter table public.anylap_live enable row level security;
create policy "own live sessions: read" on public.anylap_live for select to authenticated using (user_id = (select auth.uid()));
create policy "own live sessions: update" on public.anylap_live for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy "own live sessions: delete" on public.anylap_live for delete to authenticated using (user_id = (select auth.uid()));

create table public.anylap_live_viewers (
  code text not null references public.anylap_live (code) on delete cascade,
  device text not null check (length(device) between 8 and 64),
  user_id uuid references auth.users on delete cascade,
  pro boolean not null default false,
  joined_at timestamptz not null default now(),
  last_seen timestamptz not null default now(),
  primary key (code, device)
);
create index anylap_live_viewers_user on public.anylap_live_viewers (user_id);
alter table public.anylap_live_viewers enable row level security;
-- the camera phone sees who is watching its session; viewers go through the RPC only
create policy "live viewers: host reads" on public.anylap_live_viewers for select to authenticated
  using (exists (select 1 from public.anylap_live l where l.code = anylap_live_viewers.code and l.user_id = (select auth.uid())));

-- Effective Pro for a user: entitlement, or (open beta only) what the app claims.
create or replace function public.anylap_effective_pro(p_user uuid, p_claim boolean)
returns boolean language sql stable security definer set search_path = '' as $$
  select case
    when coalesce((select c.open_beta from public.anylap_config c where c.id = 1), false) then coalesce(p_claim, false)
    else p_user is not null and exists (
      select 1 from public.anylap_entitlements e where e.user_id = p_user and (e.expires_at is null or e.expires_at > now()))
  end;
$$;

-- New live session for the caller (signed in, a guest account is enough; their older ones are removed).
create or replace function public.anylap_live_start(p_pro boolean default false)
returns text language plpgsql security definer set search_path = '' as $$
declare
  alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  c text;
begin
  if (select auth.uid()) is null then raise exception 'not signed in'; end if;
  delete from public.anylap_live l where l.user_id = (select auth.uid()) or l.expires_at < now();
  loop
    c := '';
    for i in 1..6 loop
      c := c || substr(alphabet, 1 + floor(random() * length(alphabet))::int, 1);
    end loop;
    exit when not exists (select 1 from public.anylap_live l where l.code = c);
  end loop;
  insert into public.anylap_live (code, user_id, host_pro)
    values (c, (select auth.uid()), public.anylap_effective_pro((select auth.uid()), p_pro));
  return c;
end $$;

-- Join / keep watching (call every second or so). p_device = a random id the viewer keeps.
-- Returns the snapshot as this viewer may see it, or {"error": "gone" | "full"}.
create or replace function public.anylap_live_join(p_code text, p_device text, p_pro boolean default false)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  l public.anylap_live%rowtype;
  me uuid := (select auth.uid());
  vpro boolean;
  others int;
  rows jsonb;
begin
  select * into l from public.anylap_live where code = upper(trim(p_code)) and expires_at > now();
  if not found then return jsonb_build_object('error', 'gone'); end if;
  if p_device is null or length(p_device) < 8 or length(p_device) > 64 then return jsonb_build_object('error', 'device'); end if;
  vpro := public.anylap_effective_pro(me, p_pro);
  -- a Free camera has room for one viewer at a time (a viewer quiet for 30 s has left)
  select count(*) into others from public.anylap_live_viewers v
    where v.code = l.code and v.device <> p_device and v.last_seen > now() - interval '30 seconds';
  if not l.host_pro and others >= 1 then return jsonb_build_object('error', 'full', 'hostPro', false); end if;
  insert into public.anylap_live_viewers (code, device, user_id, pro) values (l.code, p_device, me, vpro)
    on conflict (code, device) do update set last_seen = now(), user_id = excluded.user_id, pro = excluded.pro;
  -- expanded stats only for Pro camera + Pro viewer + Pro car owner
  select coalesce(jsonb_agg((
           case when l.host_pro and vpro and (
                  r->>'owner' is null or (r->>'owner')::uuid = l.user_id
                  or public.anylap_effective_pro((r->>'owner')::uuid, null))
                then r else r - 'stats' end) - 'owner'), '[]'::jsonb)   -- owner ids never leave the server
    into rows from jsonb_array_elements(coalesce(l.snapshot->'rows', '[]'::jsonb)) r;
  return (l.snapshot - 'rows') || jsonb_build_object(
    'rows', rows, 'updated_at', l.updated_at, 'hostPro', l.host_pro, 'viewerPro', vpro,
    'viewers', others + 1);
end $$;

revoke execute on function public.anylap_effective_pro(uuid, boolean), public.anylap_live_start(boolean), public.anylap_live_join(text, text, boolean)
  from public, anon, authenticated;
grant execute on function public.anylap_live_start(boolean) to authenticated;
grant execute on function public.anylap_live_join(text, text, boolean) to anon, authenticated;

-- GDPR delete also clears live sessions and the viewer rows.
create or replace function public.anylap_delete_my_data()
returns void language plpgsql security definer set search_path = '' as $$
declare
  uid uuid := (select auth.uid());
begin
  if uid is null then raise exception 'not signed in'; end if;
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
