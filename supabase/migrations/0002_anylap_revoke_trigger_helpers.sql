-- Trigger helpers are never called directly (Supabase grants EXECUTE to authenticated by default).
revoke execute on function public.anylap_session_racers_guard(), public.anylap_touch_updated_at() from authenticated;
