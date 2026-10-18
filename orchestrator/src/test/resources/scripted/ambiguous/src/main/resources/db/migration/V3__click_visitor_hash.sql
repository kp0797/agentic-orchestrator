-- Unique visitors: a per-day pseudonymous visitor id. Never the IP address or the user agent.
-- Clicks recorded before this migration have no hash and count as clicks only.
ALTER TABLE click_events ADD COLUMN visitor_hash CHAR(64);
