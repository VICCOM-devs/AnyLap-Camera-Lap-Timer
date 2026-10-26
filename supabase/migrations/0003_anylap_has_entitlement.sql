-- Client-facing "do I own Pro?" (store purchase or grant). Ignores open_beta, which only relaxes server writes.
create or replace function public.anylap_has_entitlement()
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.anylap_entitlements e
    where e.user_id = (select auth.uid()) and (e.expires_at is null or e.expires_at > now())
  );
$$;
revoke execute on function public.anylap_has_entitlement() from public, anon;
grant execute on function public.anylap_has_entitlement() to authenticated;
