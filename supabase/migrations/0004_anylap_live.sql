-- Live timing boards: the host phone writes a small snapshot of the timing tower,
-- spectators read it by code through anylap_live_get (no direct table access for anon).
create table public.anylap_live (
  code text primary key,
  user_id uuid not null references auth.users on delete cascade default auth.uid(),
  snapshot jsonb not null default '{}'::jsonb check (pg_column_size(snapshot) < 32768),
  updated_at timestamptz not null default now(),
  expires_at timestamptz not null default now() + interval '12 hours'
);
create index anylap_live_user on public.anylap_live (user_id);
create trigger anylap_live_touch before update on public.anylap_live
  for each row execute function public.anylap_touch_updated_at();
alter table public.anylap_live enable row level security;
create policy "own live boards: read" on public.anylap_live for select to authenticated using (user_id = (select auth.uid()));
create policy "own live boards: update (pro)" on public.anylap_live for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()) and public.anylap_has_pro());
create policy "own live boards: delete" on public.anylap_live for delete to authenticated using (user_id = (select auth.uid()));

-- New board for the caller (their older boards are removed). Returns the 6-character code.
create or replace function public.anylap_live_start()
returns text language plpgsql security definer set search_path = '' as $$
declare
  alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  c text;
begin
  if (select auth.uid()) is null then raise exception 'not signed in'; end if;
  if not public.anylap_has_pro() then raise exception 'pro required'; end if;
  delete from public.anylap_live l where l.user_id = (select auth.uid()) or l.expires_at < now();
  loop
    c := '';
    for i in 1..6 loop
      c := c || substr(alphabet, 1 + floor(random() * length(alphabet))::int, 1);
    end loop;
    exit when not exists (select 1 from public.anylap_live l where l.code = c);
  end loop;
  insert into public.anylap_live (code, user_id) values (c, (select auth.uid()));
  return c;
end $$;

-- Public read by code (spectator page uses the publishable key).
create or replace function public.anylap_live_get(p_code text)
returns jsonb language sql stable security definer set search_path = '' as $$
  select l.snapshot || jsonb_build_object('updated_at', l.updated_at)
  from public.anylap_live l
  where l.code = upper(trim(p_code)) and l.expires_at > now();
$$;

revoke execute on function public.anylap_live_start(), public.anylap_live_get(text) from public, anon, authenticated;
grant execute on function public.anylap_live_start() to authenticated;
grant execute on function public.anylap_live_get(text) to anon, authenticated;

-- GDPR delete also clears live boards.
create or replace function public.anylap_delete_my_data()
returns void language plpgsql security definer set search_path = '' as $$
declare
  uid uuid := (select auth.uid());
begin
  if uid is null then raise exception 'not signed in'; end if;
  delete from public.anylap_live where user_id = uid;
  delete from public.anylap_session_racers where racer_owner = uid;
  delete from public.anylap_sessions where user_id = uid;
  delete from public.anylap_racer_shares where user_id = uid;
  delete from public.anylap_racer_links where user_id = uid;
  delete from public.anylap_racers where user_id = uid;
  delete from public.anylap_entitlements where user_id = uid;
  delete from public.anylap_profiles where user_id = uid;
end $$;
