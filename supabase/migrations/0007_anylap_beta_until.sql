-- Open beta with an end date (Reddit / closed-test release 0.5.0, Nov 2026).
-- While open_beta is true and now() < beta_pro_until, every user is Pro on the server and the app
-- (the app reads this row anonymously – see pro/Beta.kt – and hides every plan, price and paywall).
-- Extend the beta with one update, no app release:
--   update public.anylap_config set beta_pro_until = '2027-03-31 23:59:59+00' where id = 1;
-- End it early: set open_beta = false (purchases / grants in anylap_entitlements still count).

alter table public.anylap_config
  add column if not exists beta_pro_until timestamptz;

update public.anylap_config set beta_pro_until = '2027-01-31 23:59:59+00', open_beta = true where id = 1;

create or replace function public.anylap_beta_open()
returns boolean language sql stable security definer set search_path = '' as $$
  select coalesce((
    select c.open_beta and (c.beta_pro_until is null or now() < c.beta_pro_until)
    from public.anylap_config c where c.id = 1
  ), false);
$$;
revoke execute on function public.anylap_beta_open() from public, anon;
grant execute on function public.anylap_beta_open() to authenticated;

-- Server writes (racers, sessions) need Pro: beta or an entitlement.
create or replace function public.anylap_has_pro()
returns boolean language sql stable security definer set search_path = '' as $$
  select public.anylap_beta_open()
      or exists (
        select 1 from public.anylap_entitlements e
        where e.user_id = (select auth.uid()) and (e.expires_at is null or e.expires_at > now())
      );
$$;

-- Live sessions / viewers: during the beta the app's claimed tier counts (every app says Pro).
create or replace function public.anylap_effective_pro(p_user uuid, p_claim boolean)
returns boolean language sql stable security definer set search_path = '' as $$
  select case
    when public.anylap_beta_open() then coalesce(p_claim, false)
    else p_user is not null and exists (
      select 1 from public.anylap_entitlements e where e.user_id = p_user and (e.expires_at is null or e.expires_at > now()))
  end;
$$;
